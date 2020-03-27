package loghub;

import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.compress.compressors.CompressorException;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

public class Compressor extends AbstractCompDecomp {

    @EqualsAndHashCode(callSuper=true) @ToString
    public static class Builder extends AbstractCompDecomp.Builder<Compressor> {
        @Setter
        protected String format;
         public Compressor build() {
            return new Compressor(this);
        }
    };
    public static Builder getBuilder() {
        return new Builder();
    }


    @Getter
    protected final String format;

    public Compressor(Builder builder) {
        super(builder);
        this.format = builder.format;
    }

    protected InputStream source(InputStream wrappedInput) throws CompressorException {
        return wrappedInput;
    }

    protected OutputStream destination(OutputStream wrappedOutput) throws CompressorException {
        return csf.createCompressorOutputStream(format, wrappedOutput);
    }

}
