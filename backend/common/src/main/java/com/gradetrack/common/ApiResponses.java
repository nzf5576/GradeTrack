package com.gradetrack.common;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public final class ApiResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> JSON_HEADERS = Map.of(
            "Content-Type", "application/json",
            // Dev-only: frontend runs on a different origin (localhost / CloudFront later).
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "Content-Type,Authorization",
            "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS"
    );

    private ApiResponses() {
    }

    public static APIGatewayProxyResponseEvent ok(Object body) {
        return json(200, body);
    }

    public static APIGatewayProxyResponseEvent created(Object body) {
        return json(201, body);
    }

    public static APIGatewayProxyResponseEvent error(ApiException e) {
        return json(e.statusCode(), Map.of("error", e.getMessage()));
    }

    public static APIGatewayProxyResponseEvent internalError() {
        return json(500, Map.of("error", "Internal server error"));
    }

    public static APIGatewayProxyResponseEvent notFoundResponse() {
        return json(404, Map.of("error", "Not found"));
    }

    public static APIGatewayProxyResponseEvent json(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(JSON_HEADERS)
                    .withBody(MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response body", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
