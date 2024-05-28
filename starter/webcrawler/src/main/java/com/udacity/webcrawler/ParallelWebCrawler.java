package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;
  private final int maxDepth;
  private final PageParserFactory parserFactory;
  private final List<Pattern> ignoredUrls;

  @Inject
  ParallelWebCrawler(
          Clock clock,
          PageParserFactory parserFactory,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @TargetParallelism int threadCount,
          @MaxDepth int maxDepth,
          @IgnoredUrls List<Pattern> ignoredUrls
  ) {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.maxDepth = maxDepth;
    this.parserFactory = Objects.requireNonNull(parserFactory);
    this.ignoredUrls = Objects.requireNonNull(ignoredUrls);
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    final Instant deadline = clock.instant().plus(timeout);
    final ConcurrentMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    final Set<String> visitedUrls = new ConcurrentSkipListSet<>();

    startingUrls
            .stream()
            .map(url -> new ParallelCrawlJob(
                    deadline,
                    counts,
                    visitedUrls,
                    url,
                    this.maxDepth
            ))
            .forEach(this.pool::invoke);

    final Map<String, Integer> wordCounts = WordCounts.sort(
            counts
                    .entrySet()
                    .stream()
                    .collect(
                            Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry -> entry.getValue().get()
                            )
                    ),
            popularWordCount
    );

    return new CrawlResult.Builder()
            .setUrlsVisited(visitedUrls.size())
            .setWordCounts(wordCounts)
            .build();
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }

  private final class ParallelCrawlJob extends RecursiveTask<Void> {
    private final int depth;
    private final Map<String, AtomicInteger> counts;
    private final Set<String> visitedUrls;
    private final Instant deadline;
    private final String url;
    private ParallelCrawlJob(
            Instant deadline,
            Map<String, AtomicInteger> counts,
            Set<String> visitedUrls,
            String url,
            int depth
    ) {
      this.depth = depth;
      this.counts = Objects.requireNonNull(counts);
      this.visitedUrls = Objects.requireNonNull(visitedUrls);
      this.deadline = Objects.requireNonNull(deadline);
      this.url = Objects.requireNonNull(url);
    }

    @Override
    protected Void compute() {
      if (this.depth <= 0 || ParallelWebCrawler.this.clock.instant().isAfter(this.deadline) || ParallelWebCrawler.this.ignoredUrls.stream().anyMatch(pattern -> pattern.matcher(this.url).matches()) || !this.visitedUrls.add(this.url)) {
        return null;
      }

      final PageParser.Result result = parserFactory.get(this.url).parse();
      result.getWordCounts().forEach((key, value) -> this.getCountByWordCountEntry(key).addAndGet(value));

      List<ParallelCrawlJob> parallelCrawlJobs = result
              .getLinks()
              .stream()
              .map(path -> new ParallelCrawlJob(
                      this.deadline,
                      this.counts,
                      this.visitedUrls,
                      path,
                      this.depth - 1
              ))
              .toList();
      RecursiveTask.invokeAll(parallelCrawlJobs);

      for (final ParallelCrawlJob job : parallelCrawlJobs) {
        try {
          job.get();
        } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
        }
      }
      return null;
    }

    private synchronized AtomicInteger getCountByWordCountEntry(String key) {
      final AtomicInteger result = this.counts.get(key);
      if (null != result) {
        return result;
      }

      final AtomicInteger i = new AtomicInteger(0);
      ParallelCrawlJob.this.counts.put(key, i);
      return i;
    }
  }
}
