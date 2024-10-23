package org.apache.tika.pipes.fetcher;

import java.io.InputStream;
import java.util.Map;

import org.pf4j.ExtensionPoint;

public interface Fetcher extends ExtensionPoint {
    InputStream fetch(String fetchKey, Map<String, Object> fetchMetadata, Map<String, Object> responseMetadata);
}
