/*
 * Copyright 2015 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.webindex.data.fluo.it;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import io.fluo.api.client.FluoClient;
import io.fluo.api.client.FluoFactory;
import io.fluo.api.client.LoaderExecutor;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.api.data.Bytes;
import io.fluo.api.data.RowColumn;
import io.fluo.api.data.RowColumnValue;
import io.fluo.recipes.test.AccumuloExportITBase;
import io.fluo.recipes.test.FluoITHelper;
import io.fluo.webindex.core.models.Link;
import io.fluo.webindex.core.models.Page;
import io.fluo.webindex.core.models.URL;
import io.fluo.webindex.data.SparkTestUtil;
import io.fluo.webindex.data.fluo.PageLoader;
import io.fluo.webindex.data.fluo.UriMap.UriInfo;
import io.fluo.webindex.data.spark.Hex;
import io.fluo.webindex.data.spark.IndexEnv;
import io.fluo.webindex.data.spark.IndexStats;
import io.fluo.webindex.data.spark.IndexUtil;
import io.fluo.webindex.data.util.ArchiveUtil;
import io.fluo.webindex.data.util.DataUrl;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveRecord;
import org.archive.io.warc.WARCReaderFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

public class IndexIT extends AccumuloExportITBase {

  private static final Logger log = LoggerFactory.getLogger(IndexIT.class);
  private transient JavaSparkContext ctx;
  private IndexEnv env;
  private String exportTable;

  private static final int TEST_SPLITS = 119;

  @Override
  protected void preFluoInitHook() throws Exception {
    FluoConfiguration config = getFluoConfiguration();
    config.setApplicationName("lit");
    config.setWorkerThreads(5);

    // create and configure export table
    exportTable = "export" + tableCounter.getAndIncrement();

    ctx = SparkTestUtil.getSparkContext(getClass().getSimpleName());
    env = new IndexEnv(config, exportTable, "/tmp", TEST_SPLITS, TEST_SPLITS);
    env.initAccumuloIndexTable();
    env.configureApplication(config);
  }

  @Override
  protected void postFluoInitHook() throws Exception {
    env.setFluoTableSplits();
  }

  @After
  public void tearCloseContext() throws Exception {
    ctx.close();
    ctx = null;
  }

  public static Map<URL, Page> readPages(File input) throws Exception {
    Map<URL, Page> pageMap = new HashMap<>();
    ArchiveReader ar = WARCReaderFactory.get(input);
    for (ArchiveRecord r : ar) {
      Page p = ArchiveUtil.buildPage(r);
      if (p.isEmpty() || p.getOutboundLinks().isEmpty()) {
        continue;
      }
      pageMap.put(URL.fromPageID(p.getPageID()), p);
    }
    ar.close();
    return pageMap;
  }

  private void assertOutput(Collection<Page> pages) throws Exception {
    JavaRDD<Page> pagesRDD = ctx.parallelize(new ArrayList<>(pages));
    Assert.assertEquals(pages.size(), pagesRDD.count());

    // Create expected output using spark
    IndexStats stats = new IndexStats(ctx);

    JavaPairRDD<String, UriInfo> uriMap = IndexUtil.createUriMap(pagesRDD);
    JavaPairRDD<String, Long> domainMap = IndexUtil.createDomainMap(uriMap);
    JavaPairRDD<RowColumn, Bytes> accumuloIndex =
        IndexUtil.createAccumuloIndex(stats, pagesRDD, uriMap, domainMap).sortByKey();
    JavaPairRDD<RowColumn, Bytes> fluoIndex =
        IndexUtil.createFluoTable(pagesRDD, uriMap, domainMap, TEST_SPLITS).sortByKey();

    // Compare against actual
    try (FluoClient client = FluoFactory.newClient(getMiniFluo().getClientConfiguration())) {
      boolean foundDiff =
          !FluoITHelper.verifyAccumuloTable(getAccumuloConnector(), exportTable,
              tuples2rcv(accumuloIndex.collect()));
      foundDiff |= !FluoITHelper.verifyFluoTable(client, tuples2rcv(fluoIndex.collect()));
      if (foundDiff) {
        FluoITHelper.printFluoTable(client);
        FluoITHelper.printAccumuloTable(getAccumuloConnector(), exportTable);
        printRDD(accumuloIndex.collect());
        printRDD(fluoIndex.collect());
      }
      Assert.assertFalse(foundDiff);
    }
  }

  public static Link newLink(String url) {
    return Link.of(DataUrl.from(url));
  }

  public static Link newLink(String url, String anchorText) {
    return Link.of(DataUrl.from(url), anchorText);
  }

  @Test
  public void testFluoIndexing() throws Exception {

    Map<URL, Page> pages = readPages(new File("src/test/resources/wat-18.warc"));

    try (FluoClient client = FluoFactory.newClient(getMiniFluo().getClientConfiguration())) {

      try (LoaderExecutor le = client.newLoaderExecutor()) {
        for (Page page : pages.values()) {
          log.debug("Loading page {} with {} links", page.getUrl(), page.getOutboundLinks().size());
          le.execute(PageLoader.updatePage(page));
        }
      }

      getMiniFluo().waitForObservers();
      assertOutput(pages.values());

      URL deleteUrl = DataUrl.from("http://1000games.me/games/gametion/");
      log.info("Deleting page {}", deleteUrl);
      try (LoaderExecutor le = client.newLoaderExecutor()) {
        le.execute(PageLoader.deletePage(deleteUrl));
      }
      getMiniFluo().waitForObservers();

      int numPages = pages.size();
      Assert.assertNotNull(pages.remove(deleteUrl));
      Assert.assertEquals(numPages - 1, pages.size());
      assertOutput(pages.values());

      URL updateUrl = DataUrl.from("http://100zone.blogspot.com/2013/03/please-memp3-4shared.html");
      Page updatePage = pages.get(updateUrl);
      long numLinks = updatePage.getNumOutbound();
      Assert.assertTrue(updatePage.addOutbound(newLink("http://example.com", "Example")));
      Assert.assertEquals(numLinks + 1, (long) updatePage.getNumOutbound());
      Assert.assertTrue(updatePage.removeOutbound(newLink("http://www.blogger.com")));
      Assert.assertEquals(numLinks, (long) updatePage.getNumOutbound());

      try (LoaderExecutor le = client.newLoaderExecutor()) {
        le.execute(PageLoader.updatePage(updatePage));
      }
      getMiniFluo().waitForObservers();

      // create a URL that has an inlink count of 2
      URL updateUrl2 = DataUrl.from("http://00assclown.newgrounds.com/");
      Page updatePage2 = pages.get(updateUrl2);
      long numLinks2 = updatePage2.getNumOutbound();
      Assert.assertTrue(updatePage2.addOutbound(newLink("http://example.com", "Example")));
      Assert.assertEquals(numLinks2 + 1, (long) updatePage2.getNumOutbound());

      try (LoaderExecutor le = client.newLoaderExecutor()) {
        le.execute(PageLoader.updatePage(updatePage2));
      }
      getMiniFluo().waitForObservers();

      Assert.assertNotNull(pages.put(updateUrl, updatePage));
      Assert.assertNotNull(pages.put(updateUrl2, updatePage2));
      assertOutput(pages.values());

      // completely remove link that had an inlink count of 2
      updatePage = pages.get(updateUrl);
      numLinks = updatePage.getNumOutbound();
      Assert.assertTrue(updatePage.removeOutbound(newLink("http://example.com")));
      Assert.assertEquals(numLinks - 1, (long) updatePage.getNumOutbound());

      updatePage2 = pages.get(updateUrl2);
      numLinks2 = updatePage2.getNumOutbound();
      Assert.assertTrue(updatePage2.removeOutbound(newLink("http://example.com")));
      Assert.assertEquals(numLinks2 - 1, (long) updatePage2.getNumOutbound());

      try (LoaderExecutor le = client.newLoaderExecutor()) {
        le.execute(PageLoader.updatePage(updatePage));
        le.execute(PageLoader.updatePage(updatePage2));
      }
      getMiniFluo().waitForObservers();

      Assert.assertNotNull(pages.put(updateUrl, updatePage));
      Assert.assertNotNull(pages.put(updateUrl2, updatePage2));
      assertOutput(pages.values());
    }
  }

  @Test
  public void testSparkThenFluoIndexing() throws Exception {

    Map<URL, Page> pageMap = readPages(new File("src/test/resources/wat-18.warc"));
    List<Page> pages = new ArrayList<>(pageMap.values());

    env.initializeIndexes(ctx, ctx.parallelize(pages.subList(0, 2)), new IndexStats(ctx));

    assertOutput(pages.subList(0, 2));

    try (FluoClient client = FluoFactory.newClient(getMiniFluo().getClientConfiguration());
        LoaderExecutor le = client.newLoaderExecutor()) {
      for (Page page : pages.subList(2, pages.size())) {
        log.info("Loading page {} with {} links {}", page.getUrl(), page.getOutboundLinks().size(),
            page.getOutboundLinks());
        le.execute(PageLoader.updatePage(page));
      }
    }
    getMiniFluo().waitForObservers();

    assertOutput(pages);
  }

  private void printRDD(List<Tuple2<RowColumn, Bytes>> rcvRDD) {
    System.out.println("== RDD start ==");
    rcvRDD.forEach(t -> System.out.println("rc " + Hex.encNonAscii(t, " ")));
    System.out.println("== RDD end ==");
  }

  private static List<RowColumnValue> tuples2rcv(List<Tuple2<RowColumn, Bytes>> linkIndex) {
    return Lists.transform(linkIndex, t -> new RowColumnValue(t._1().getRow(), t._1().getColumn(),
        t._2()));
  }
}
