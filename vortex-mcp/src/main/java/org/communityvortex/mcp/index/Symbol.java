package org.communityvortex.mcp.index;

/**
 * A declaration found in the workspace.
 *
 * @param kind {@code class}, {@code interface}, {@code enum}, {@code record}, {@code method}, {@code constructor},
 *             {@code field}, {@code function}, {@code variable}, {@code type}
 */
public record Symbol(String name, String qualifiedName, String kind, String file, int line, int endLine, String signature) {
}
