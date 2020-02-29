package com.github.mmolimar.kafka.connect.fs.file.reader;

import com.github.mmolimar.kafka.connect.fs.file.Offset;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import static com.github.mmolimar.kafka.connect.fs.FsSourceTaskConfig.FILE_READER_PREFIX;

public class TextFileReader extends AbstractFileReader<TextFileReader.TextRecord> {

    private static final String FILE_READER_TEXT = FILE_READER_PREFIX + "text.";
    private static final String FILE_READER_FIELD_NAME_PREFIX = FILE_READER_TEXT + "field_name.";

    public static final String FIELD_NAME_VALUE_DEFAULT = "value";
    public static final String FILE_READER_TEXT_FIELD_NAME_VALUE = FILE_READER_FIELD_NAME_PREFIX + "value";
    public static final String FILE_READER_TEXT_RECORD_PER_LINE = FILE_READER_TEXT + "record_per_line";
    public static final String FILE_READER_TEXT_ENCODING = FILE_READER_TEXT + "encoding";

    private final TextOffset offset;
    private String current;
    private boolean finished = false;
    private LineNumberReader reader;
    private Schema schema;
    private Charset charset;
    private boolean recordPerLine;

    public TextFileReader(FileSystem fs, Path filePath, Map<String, Object> config) throws IOException {
        super(fs, filePath, new TxtToStruct(), config);
        this.reader = new LineNumberReader(new InputStreamReader(fs.open(filePath), this.charset));
        this.offset = new TextOffset(0);
    }

    @Override
    protected void configure(Map<String, Object> config) {
        String valueFieldName;
        if (config.get(FILE_READER_TEXT_FIELD_NAME_VALUE) == null ||
                config.get(FILE_READER_TEXT_FIELD_NAME_VALUE).toString().equals("")) {
            valueFieldName = FIELD_NAME_VALUE_DEFAULT;
        } else {
            valueFieldName = config.get(FILE_READER_TEXT_FIELD_NAME_VALUE).toString();
        }
        if (config.get(FILE_READER_TEXT_ENCODING) == null ||
                config.get(FILE_READER_TEXT_ENCODING).toString().equals("")) {
            this.charset = Charset.defaultCharset();
        } else {
            this.charset = Charset.forName(config.get(FILE_READER_TEXT_ENCODING).toString());
        }
        if (config.get(FILE_READER_TEXT_RECORD_PER_LINE) == null ||
                config.get(FILE_READER_TEXT_RECORD_PER_LINE).toString().equals("")) {
            this.recordPerLine = true;
        } else {
            this.recordPerLine = Boolean.parseBoolean(config.get(FILE_READER_TEXT_RECORD_PER_LINE).toString());
        }
        this.schema = SchemaBuilder.struct()
                .field(valueFieldName, Schema.STRING_SCHEMA)
                .build();
    }

    @Override
    public boolean hasNext() {
        if (current != null) {
            return true;
        } else if (finished) {
            return false;
        } else {
            try {
                if (!recordPerLine) {
                    List<String> lines = new BufferedReader(reader).lines().collect(Collectors.toList());
                    offset.setOffset(lines.size() - 1);
                    current = String.join("\n", lines);
                    finished = true;
                    return true;
                }
                for (; ; ) {
                    String line = reader.readLine();
                    offset.setOffset(reader.getLineNumber());
                    if (line == null) {
                        finished = true;
                        return false;
                    }
                    current = line;
                    return true;
                }
            } catch (IOException ioe) {
                throw new IllegalStateException(ioe);
            }
        }
    }

    @Override
    protected TextRecord nextRecord() {
        if (!hasNext()) {
            throw new NoSuchElementException("There are no more records in file: " + getFilePath());
        }
        String aux = current;
        current = null;

        return new TextRecord(schema, aux);
    }

    @Override
    public void seek(Offset offset) {
        if (offset.getRecordOffset() < 0) {
            throw new IllegalArgumentException("Record offset must be greater than 0");
        }
        try {
            current = null;
            if (offset.getRecordOffset() < reader.getLineNumber()) {
                finished = false;
                reader = new LineNumberReader(new InputStreamReader(getFs().open(getFilePath())));
            }
            while (reader.getLineNumber() < offset.getRecordOffset()) {
                reader.readLine();
            }
            this.offset.setOffset(reader.getLineNumber() + 1);
        } catch (IOException ioe) {
            throw new ConnectException("Error seeking file " + getFilePath(), ioe);
        }
    }

    @Override
    public Offset currentOffset() {
        return offset;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    public static class TextOffset implements Offset {
        private long offset;

        public TextOffset(long offset) {
            this.offset = offset;
        }

        public void setOffset(long offset) {
            this.offset = offset;
        }

        @Override
        public long getRecordOffset() {
            return offset;
        }
    }

    static class TxtToStruct implements ReaderAdapter<TextRecord> {

        @Override
        public Struct apply(TextRecord record) {
            return new Struct(record.schema)
                    .put(record.schema.fields().get(0), record.value);
        }
    }

    static class TextRecord {
        private final Schema schema;
        private final String value;

        TextRecord(Schema schema, String value) {
            this.schema = schema;
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
