package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.influora.integration.meta.oauth.MetaDiagnostics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business Login for Instagram code-exchange response (T-IGLOGIN-0820).
 *
 * <p>Deliberately NOT {@link MetaTokenResponse}: the Instagram short-lived exchange returns a
 * different body. It carries {@code user_id} — the Instagram user id — which on this path REPLACES
 * the {@code GET /me/accounts} lookup the Facebook path uses to find a linked Page's
 * {@code instagram_business_account}. There is no Page here to look one up from, so losing this
 * field means losing the account id entirely.
 *
 * <p>It also has no {@code expires_in}: the short-lived token is ~1 hour and callers must
 * immediately exchange it for a long-lived one, which does report an expiry.
 *
 * <h2>F-0818 — the response is wrapped in a {@code data} array</h2>
 *
 * <p>This record originally bound the three fields at the TOP level, which is the shape the older
 * Basic Display exchange returned. Business Login returns them inside a single-element
 * {@code data} array instead:
 *
 * <pre>
 *   {"data":[{"access_token":"IGAA…","user_id":17841400000000001,
 *             "permissions":"instagram_business_basic,instagram_business_manage_insights"}]}
 * </pre>
 *
 * <p>With {@code ignoreUnknown = true} and no top-level {@code access_token}, Jackson would build a
 * record whose every component is {@code null} <b>and throw nothing</b>.
 *
 * <p><b>Correction, 2026-09-17:</b> this was believed to cause the production failure
 * ({@code instagram-long-lived-exchange} 400 "Unsupported request - method type: get"). It did
 * not. With this fix deployed, a real connect produced a non-blank token and graph.instagram.com
 * returned the identical error. Which shape Meta actually sends was never observed — the
 * deserializer below now logs it (F-0819), and the real cause is still being diagnosed.
 *
 * <p>The deserializer therefore accepts <b>both</b> shapes rather than swapping one guess for
 * another — a wrapped body is unwrapped, a flat body is read as-is. Same for {@code permissions},
 * which Business Login documents as a COMMA-SEPARATED STRING while the older shape used a JSON
 * array; both parse to the same {@code List}. And {@code user_id} arrives as a JSON NUMBER here,
 * so it is read with {@code asText()} rather than bound to a {@code String} component directly.
 *
 * <p>{@code @JsonIgnoreProperties} is kept as defence in depth: it is inert while the custom
 * deserializer is in place, and restores lenient binding if the annotation is ever removed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = InstagramShortLivedTokenResponse.Deserializer.class)
public record InstagramShortLivedTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("user_id") String userId,
        @JsonProperty("permissions") List<String> permissions) {

    /** Reads either the {@code data}-wrapped Business Login body or the older flat one. */
    static final class Deserializer extends JsonDeserializer<InstagramShortLivedTokenResponse> {

        private static final Logger log = LoggerFactory.getLogger(InstagramShortLivedTokenResponse.class);

        @Override
        public InstagramShortLivedTokenResponse deserialize(
                JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode root = parser.readValueAsTree();
            JsonNode body = unwrap(root);
            InstagramShortLivedTokenResponse parsed =
                    new InstagramShortLivedTokenResponse(
                            text(body, "access_token"),
                            text(body, "user_id"),
                            permissions(body.get("permissions")));
            // F-0819 — record what Meta actually sent, as names and fingerprints only. The F-0818
            // fix rested on a documented shape nobody had observed; this line observes it.
            log.info(
                    "F-0819 instagram code-exchange response: shape={}, topLevelKeys={}, bodyKeys={},"
                            + " accessToken={}, userIdPresent={}, permissions={}",
                    shapeOf(root),
                    fieldNames(root),
                    fieldNames(body),
                    MetaDiagnostics.tokenFingerprint(parsed.accessToken()),
                    parsed.userId() != null,
                    parsed.permissions());
            return parsed;
        }

        private static String shapeOf(JsonNode root) {
            if (root == null || !root.isObject()) {
                return "not-an-object";
            }
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                return data.isEmpty() ? "data-wrapped-empty" : "data-wrapped";
            }
            return "flat";
        }

        private static List<String> fieldNames(JsonNode node) {
            List<String> names = new ArrayList<>();
            if (node != null && node.isObject()) {
                node.fieldNames().forEachRemaining(names::add);
            }
            return names;
        }

        /**
         * The first {@code data} element, or the node itself when there is no wrapper. An empty
         * {@code data} array yields an empty node so every field comes back null — the caller's
         * null guard reports that, rather than an index-out-of-bounds here.
         */
        private static JsonNode unwrap(JsonNode root) {
            if (root == null || !root.isObject()) {
                return MissingNode.getInstance();
            }
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                return data.isEmpty()
                        ? MissingNode.getInstance()
                        : data.get(0);
            }
            return root;
        }

        private static String text(JsonNode body, String field) {
            JsonNode value = body.get(field);
            if (value == null || value.isNull()) {
                return null;
            }
            // asText(), not textValue(): user_id is a JSON number in the Business Login body and
            // textValue() returns null for a non-textual node.
            String text = value.asText();
            return text.isBlank() ? null : text;
        }

        /** Accepts {@code "a,b"} (Business Login) or {@code ["a","b"]} (the older shape). */
        private static List<String> permissions(JsonNode value) {
            if (value == null || value.isNull()) {
                return List.of();
            }
            List<String> scopes = new ArrayList<>();
            if (value.isArray()) {
                value.forEach(element -> addIfPresent(scopes, element.asText()));
            } else {
                for (String element : value.asText().split(",")) {
                    addIfPresent(scopes, element);
                }
            }
            return List.copyOf(scopes);
        }

        private static void addIfPresent(List<String> scopes, String scope) {
            if (scope != null && !scope.isBlank()) {
                scopes.add(scope.trim());
            }
        }
    }
}
