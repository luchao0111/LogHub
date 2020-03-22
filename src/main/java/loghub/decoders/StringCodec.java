package loghub.decoders;

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import loghub.BuilderClass;
import loghub.ConnectionContext;
import lombok.Setter;

@BuilderClass(StringCodec.Builder.class)
public class StringCodec extends Decoder {

    public static class Builder extends Decoder.Builder<StringCodec> {
        @Setter
        private String charset = Charset.defaultCharset().name();
        @Override
        public StringCodec build() {
            return new StringCodec(this);
        }
    };

    public static Builder getBuilder() {
        return new Builder();
    }

    private final Charset charset;

    private StringCodec(Builder builder) {
        super(builder);
        charset = Charset.forName(builder.charset);
    }

    @Override
    protected Object decodeObject(ConnectionContext<?> connectionContext,
                                  byte[] msg, int offset, int length)
                                                  throws DecodeException {
        return new String(msg, offset, length, charset);
    }

    @Override
    protected Object decodeObject(ConnectionContext<?> ctx, ByteBuf bbuf)
                    throws DecodeException {
        return bbuf.toString(charset);
    }

}
