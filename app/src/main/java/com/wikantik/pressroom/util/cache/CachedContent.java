
package com.wikantik.pressroom.util.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A utility class for storing cached content written to a java.io.Writer.
 */
public class CachedContent implements AutoCloseable, Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(CachedContent.class);
    
    // the byte array we use to maintain the cached content
    private byte[] content = new byte[0];
    
    // when this was built, epoch millis; caches compare it to the discovery generation
    private final long createdAt = System.currentTimeMillis();

    // content-type of data in byte array
    private final String contentType;
    
    // Use a byte array output stream to cached the output bytes
    private transient ByteArrayOutputStream outstream = null;
    
    // The PrintWriter that users will be writing to
    private transient PrintWriter cachedWriter = null;
    
    
    public CachedContent(int size) {
        this(size, null);
    }
    
    public CachedContent(int size, String contentType) {
        // construct output stream
        if(size > 0) {
            this.outstream = new ByteArrayOutputStream(size);
        } else {
            this.outstream = new ByteArrayOutputStream(PressroomConstants.EIGHT_KB_IN_BYTES);
        }
        
        // construct writer from output stream
        this.cachedWriter = new PrintWriter(new OutputStreamWriter(this.outstream, UTF_8));
        this.contentType = contentType;
    }
    
    
    /**
     * Get the content cached in this object as a byte array.  If you convert
     * this back to a string yourself, be sure to re-encode in "UTF-8".
     *
     * NOTE: the content is only a representation of the data written to the
     *       enclosed Writer up until the last call to flush().
     */
    public byte[] getContent() {
        return this.content;
    }
    
    
    /**
     * Get the content cached in this object as a String.
     *
     * NOTE: the content is only a representation of the data written to the
     *       enclosed Writer up until the last call to flush().
     */
    public String getContentAsString() {
        return new String(this.content, UTF_8);
    }
    
    
    public PrintWriter getCachedWriter() {
        return cachedWriter;
    }
    
    
    /** When this content was built, in epoch milliseconds. */
    public long getCreatedAt() {
        return createdAt;
    }


    public String getContentType() {
        return contentType;
    }
    
    
    /**
     * Called to flush any output in the cached Writer to
     * the cached content for more permanent storage.
     *
     * @throws IllegalStateException if calling flush() after a close()
     */
    public void flush() {
        
        if(this.outstream == null) {
            throw new IllegalStateException("Cannot flush() after a close()!");
        }
        
        this.cachedWriter.flush();
        this.content = this.outstream.toByteArray();
        
        log.debug("FLUSHED {}", this.content.length);
    }
    
    
    /**
     * Close this CachedContent from further writing.
     */
    @Override
    public void close() throws IOException {
        
        if(this.cachedWriter != null) {
            this.cachedWriter.flush();
            this.cachedWriter.close();
            this.cachedWriter = null;
        }
        
        if(this.outstream != null) {
            // avoid copying the content again if it hasn't changed since last flush
            if(this.content.length != this.outstream.size()) {
                this.content = this.outstream.toByteArray();
            }
            this.outstream.close();
            this.outstream = null;
        }
        
        log.debug("CLOSED");
    }
    
}
