// Copy from https://github.com/aellerton/japng
// Licensed under the Apache License, Version 2.0.

package determination.xenon.ui.image.apng.map;

import determination.xenon.ui.image.apng.PngChunkCode;
import determination.xenon.ui.image.apng.PngConstants;
import determination.xenon.ui.image.apng.error.PngException;
import determination.xenon.ui.image.apng.reader.PngReader;
import determination.xenon.ui.image.apng.reader.PngSource;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Simple processor that skips all chunk content and ignores checksums, with
 * sole objective of building a map of the contents of a PNG file.
 * <p>
 * WARNING: not sure if this API will remain.
 * </p>
 */
public class PngMapReader implements PngReader<PngMap> {
    PngMap map;

    public PngMapReader(String sourceName) {
        map = new PngMap();
        map.source = sourceName;
        map.chunks = new ArrayList<>(4);
    }

    @Override
    public boolean readChunk(PngSource source, int code, int dataLength) throws PngException, IOException {
        int dataPosition = source.tell();
        source.skip(dataLength);
        int chunkChecksum = source.readInt();
        map.chunks.add(new PngChunkMap(PngChunkCode.from(code), dataPosition, dataLength, chunkChecksum));

        return code == PngConstants.IEND_VALUE;
    }

    @Override
    public void finishedChunks(PngSource source) throws PngException, IOException {
        // NOP
    }

    @Override
    public PngMap getResult() {
        return map;
    }
}
