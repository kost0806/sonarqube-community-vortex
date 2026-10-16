package org.communityvortex.mcp.index;

import java.io.IOException;
import java.nio.file.Path;

/** Lets tests in other packages build the sample workspace. */
public final class SampleWorkspaceAccess {

  private SampleWorkspaceAccess() {
  }

  public static Path create(Path root) throws IOException {
    return SampleWorkspace.create(root);
  }
}
