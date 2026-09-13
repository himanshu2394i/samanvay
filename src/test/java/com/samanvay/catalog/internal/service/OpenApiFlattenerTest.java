package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenApiFlattenerTest {

    @Test
    void flattensSelectedOperationResponseProperties() {
        String spec =
                """
                {
                  "openapi":"3.0.0",
                  "paths":{
                    "/income":{
                      "get":{
                        "operationId":"getIncome",
                        "responses":{
                          "200":{
                            "content":{
                              "application/json":{
                                "schema":{
                                  "type":"object",
                                  "properties":{
                                    "annual_income":{"type":"number"},
                                    "holder_name":{"type":"string"}
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
        assertThat(new OpenApiFlattener().sourceFields(spec, "getIncome"))
                .containsExactly("annual_income", "holder_name");
    }
}
