package io.github.laiwf94.payflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks JSON against the subset of JSON Schema the OpenAPI document uses: type, required,
 * properties, additionalProperties false, enum, pattern, min/max, minItems/maxItems, items and
 * local $ref. Small enough to read in one go, which is the point: the spec is tested against the
 * responses, not against another library's interpretation.
 */
final class SchemaValidator {

  private final Map<String, Object> spec;

  SchemaValidator(Map<String, Object> spec) {
    this.spec = spec;
  }

  List<String> validate(JsonNode value, Object schema) {
    List<String> errors = new ArrayList<>();
    check(value, schema, "$", errors);
    return errors;
  }

  @SuppressWarnings("unchecked")
  private void check(JsonNode v, Object schemaObj, String path, List<String> errors) {
    Map<String, Object> s = (Map<String, Object>) schemaObj;
    if (s.containsKey("$ref")) {
      check(v, resolve((String) s.get("$ref")), path, errors);
      return;
    }
    String type = (String) s.get("type");
    if (type != null && !typeMatches(v, type)) {
      errors.add(path + ": expected " + type + " but was " + v.getNodeType());
      return;
    }
    if (s.containsKey("enum") && !((List<Object>) s.get("enum")).contains(v.asText())) {
      errors.add(path + ": " + v.asText() + " is not one of " + s.get("enum"));
    }
    if (s.containsKey("pattern")
        && !Pattern.compile((String) s.get("pattern")).matcher(v.asText()).find()) {
      errors.add(path + ": " + v.asText() + " doesn't match " + s.get("pattern"));
    }
    if (s.containsKey("minimum") && v.asLong() < ((Number) s.get("minimum")).longValue()) {
      errors.add(path + ": below minimum");
    }
    if (s.containsKey("maximum") && v.asLong() > ((Number) s.get("maximum")).longValue()) {
      errors.add(path + ": above maximum");
    }
    if (s.containsKey("maxLength")
        && v.asText().length() > ((Number) s.get("maxLength")).intValue()) {
      errors.add(path + ": too long");
    }
    if ("object".equals(type)) {
      Map<String, Object> props = (Map<String, Object>) s.getOrDefault("properties", Map.of());
      for (Object r : (List<Object>) s.getOrDefault("required", List.of())) {
        if (!v.has((String) r)) {
          errors.add(path + ": missing required " + r);
        }
      }
      for (Map.Entry<String, JsonNode> f : v.properties()) {
        if (props.containsKey(f.getKey())) {
          check(f.getValue(), props.get(f.getKey()), path + "." + f.getKey(), errors);
        } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
          errors.add(path + ": unexpected property " + f.getKey());
        }
      }
    }
    if ("array".equals(type)) {
      if (s.containsKey("minItems") && v.size() < ((Number) s.get("minItems")).intValue()) {
        errors.add(path + ": too few items");
      }
      if (s.containsKey("maxItems") && v.size() > ((Number) s.get("maxItems")).intValue()) {
        errors.add(path + ": too many items");
      }
      for (int i = 0; i < v.size(); i++) {
        check(v.get(i), s.get("items"), path + "[" + i + "]", errors);
      }
    }
  }

  private static boolean typeMatches(JsonNode v, String type) {
    return switch (type) {
      case "object" -> v.isObject();
      case "array" -> v.isArray();
      case "string" -> v.isTextual();
      case "integer" -> v.isIntegralNumber();
      case "boolean" -> v.isBoolean();
      default -> throw new IllegalArgumentException("unsupported type " + type);
    };
  }

  /** Resolves {@code #/a/b/c} within the spec. */
  @SuppressWarnings("unchecked")
  Object resolve(String ref) {
    Object node = spec;
    for (String part : ref.substring(2).split("/", -1)) {
      node = ((Map<String, Object>) node).get(part);
      if (node == null) {
        throw new IllegalArgumentException("unresolved $ref " + ref);
      }
    }
    return node;
  }
}
