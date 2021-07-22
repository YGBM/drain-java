package com.github.bric3.drain;

import com.github.bric3.drain.MappedFileLineReader.ChannelSink;
import com.github.bric3.drain.MappedFileLineReader.IOReadAction;
import com.github.bric3.drain.MappedFileLineReader.LineConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class MappedFileLineReaderTest {
    private final Path resourceDirectory = Paths.get("src", "test", "resources");

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Test
    void should_watch_with_line_reader(@TempDir Path tmpDir) throws IOException {
        Path path = Files.createTempFile(tmpDir, "test", "log");

        LineAppender lineAppender = new LineAppender(path);
        Config config = new Config(true);

        try (MappedFileLineReader r = new MappedFileLineReader(config, new LineConsumer(line -> {}, UTF_8))) {
            Future future = scheduler.scheduleAtFixedRate(lineAppender, 0, 400, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> future.cancel(false), 4, TimeUnit.SECONDS);
            scheduler.schedule(r::close, 10, TimeUnit.SECONDS);

            r.tailRead(path, FromLine.fromStart(0), true);

            assertThat(r.totalReadBytes()).isEqualTo(lineAppender.writtenBytes);
        }
    }

    @Test
    void should_watch_with_channel_sink(@TempDir Path tmpDir) throws IOException {
        Path path = Files.createTempFile(tmpDir, "test", "log");
        LineAppender lineAppender = new LineAppender(path);
        Config config = new Config(true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MappedFileLineReader r = new MappedFileLineReader(config, new ChannelSink(Channels.newChannel(out)))) {
            Future future = scheduler.scheduleAtFixedRate(lineAppender, 0, 400, TimeUnit.MILLISECONDS);
            scheduler.schedule(() -> future.cancel(false), 4, TimeUnit.SECONDS);
            scheduler.schedule(r::close, 10, TimeUnit.SECONDS);

            r.tailRead(path, FromLine.fromStart(0), true);

            assertThat(r.totalReadBytes()).isEqualTo(lineAppender.writtenBytes);
            assertThat(path.toFile()).hasBinaryContent(out.toByteArray());
        }
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void find_start_position_given_last_lines() throws IOException {
        try (FileChannel channel = FileChannel.open(resourceDirectory.resolve("3-lines.txt"),
                                            StandardOpenOption.READ)) {
            MappedFileLineReader r = new MappedFileLineReader(new Config(true), IOReadAction.NO_OP);

            assertThat(r.findTailStartPosition(channel, FromLine.fromEnd(10))).isEqualTo(0);
            assertThat(r.findTailStartPosition(channel, FromLine.fromEnd(2))).isEqualTo(42);
            assertThat(r.findTailStartPosition(channel, FromLine.fromEnd(0))).isEqualTo(183);
            assertThat(r.findTailStartPosition(channel, FromLine.fromStart(0))).isEqualTo(0);
            assertThat(r.findTailStartPosition(channel, FromLine.fromStart(2))).isEqualTo(181);
            assertThat(r.findTailStartPosition(channel, FromLine.fromStart(10))).isEqualTo(183);
        }
    }

    @Test
    void can_read_from_position() throws IOException {
        try (FileChannel channel = FileChannel.open(resourceDirectory.resolve("3-lines.txt"),
                                            StandardOpenOption.READ)) {
            ChannelSink sink = new ChannelSink(TestSink.nullSink());
            assertThat(sink.apply(channel, 0)).isEqualTo(183);
            assertThat(sink.apply(channel, 41)).isEqualTo(142);
        }
    }

    @Test
    void cannot_read_from_negative_position() throws IOException {
        try (FileChannel channel = FileChannel.open(resourceDirectory.resolve("3-lines.txt"),
                                            StandardOpenOption.READ)) {
            ChannelSink sink = new ChannelSink(TestSink.nullSink());
            assertThatExceptionOfType(AssertionError.class).isThrownBy(
                    () -> sink.apply(channel, -1)
            );
        }
    }

    static class LineAppender implements Runnable {
        Path path;
        int lineCounter = 0;
        private int writtenBytes = 0;

        public LineAppender(Path path) {
            this.path = path;
        }

        @Override
        public void run() {
            int howManyLines = new Random().nextInt(10);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= howManyLines; i++) {
                sb.append("line ").append(lineCounter++).append("\n");
            }


            try {
                ByteBuffer encoded = UTF_8.encode(CharBuffer.wrap(sb));
                writtenBytes += encoded.capacity();
                Files.write(path, encoded.array(), StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
    private static class TestSink implements WritableByteChannel {

        int writenBytes = 0;

        static TestSink nullSink() {
            return new TestSink();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {

        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int remaining = src.remaining();
            writenBytes += remaining;
            return remaining;
        }
    }

}
