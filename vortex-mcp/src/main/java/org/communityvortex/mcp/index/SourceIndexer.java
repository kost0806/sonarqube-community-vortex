package org.communityvortex.mcp.index;

import java.util.Optional;

/** Extracts {@link SourceFacts} from one file of a given language. */
public interface SourceIndexer {

  boolean supports(String relativePath);

  /** @return facts, or empty when the file cannot be parsed */
  Optional<SourceFacts> index(String relativePath, String content);
}
