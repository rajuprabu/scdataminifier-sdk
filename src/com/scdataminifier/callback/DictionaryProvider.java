package com.scdataminifier.callback;

import com.scdataminifier.ScHeader;

/**
 * Implemented by the application; supplies the shared compression dictionary
 * for ZIP_DICT contents during parsing. The dictionary is distributed
 * out-of-band (bundled with the application) and identified by its version.
 */
public interface DictionaryProvider {

    /**
     * @param header            parsed payload header (application ID etc.)
     * @param dictionaryVersion dictionary version from the content block (0-255)
     * @return the dictionary bytes, exactly as used by the writer
     */
    byte[] getDictionary(ScHeader header, int dictionaryVersion);
}
