package com.github.bric3.drain;

import com.github.bric3.drain.MappedFileLineReader.LineConsumer;
import com.google.common.base.Stopwatch;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class DrainFile {

    private final Config config;

    public DrainFile(Config config) {
        this.config = config;
    }

    public void drain(Path file, FromLine fromLine, boolean follow) {
        assert file != null;
        assert fromLine != null;

        Drain drain = Drain.drainBuilder()
                         .additionalDelimiters("_")
                         .depth(4)
                         .build();

        AtomicInteger lineCounter = new AtomicInteger();
        Stopwatch stopwatch = Stopwatch.createStarted();
        Consumer<String> drainConsumer = l -> {
            lineCounter.incrementAndGet();

            String content = preProcess(l);
            drain.parseLogMessage(content);
            if (config.verbose && lineCounter.get() % 10000 == 0) {
                config.out.printf("%4d clusters so far%n", drain.clusters().size());
            }
        };

        new MappedFileLineReader(config, new LineConsumer(drainConsumer, config.charset))
                .tailRead(file, fromLine, follow);

        if (config.verbose) {
            config.out.printf("---- Done processing file. Total of %d lines, done in %s, %d clusters%n",
                              lineCounter.get(),
                              stopwatch,
                              drain.clusters().size());
        }
        drain.clusters()
             .stream()
             .sorted(Comparator.comparing(LogCluster::sightings).reversed())
             .forEach(System.out::println);

    }

    private String preProcess(String line) {
        int parseAfterCol = config.drain.parseAfterCol;
        if (parseAfterCol > 0) {
            return line.substring(parseAfterCol);
        }

        String parseAfterStr = config.drain.parseAfterStr;
        if (!parseAfterStr.isEmpty()) {
            return line.substring(line.indexOf(parseAfterStr) + parseAfterStr.length());
        }
        return line;
    }
}
