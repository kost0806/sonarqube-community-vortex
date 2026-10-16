package org.communityvortex.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed access to MCP tool arguments with validation errors suitable for the agent. */
final class ToolArguments {

  private final Map<String, Object> arguments;

  ToolArguments(Map<String, Object> arguments) {
    this.arguments = arguments == null ? Map.of() : arguments;
  }

  String optionalString(String name) {
    var value = arguments.get(name);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("'" + name + "' must be a string");
    }
    return text.isBlank() ? null : text.trim();
  }

  String requiredString(String name) {
    var value = optionalString(name);
    if (value == null) {
      throw new IllegalArgumentException("'" + name + "' is required");
    }
    return value;
  }

  int integer(String name, int defaultValue, int min, int max) {
    var value = arguments.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
      throw new IllegalArgumentException("'" + name + "' must be an integer");
    }
    var result = number.intValue();
    if (result < min || result > max) {
      throw new IllegalArgumentException("'" + name + "' must be between " + min + " and " + max);
    }
    return result;
  }

  List<String> stringList(String name) {
    var value = arguments.get(name);
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("'" + name + "' must be a non-empty array of strings");
    }
    var result = new ArrayList<String>();
    for (var element : list) {
      if (!(element instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("'" + name + "' must only contain non-empty strings");
      }
      result.add(text);
    }
    return result;
  }
}
