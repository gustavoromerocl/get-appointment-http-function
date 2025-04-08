package com.function.graphql;

import java.util.Map;
import java.util.Optional;

import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import static graphql.Scalars.GraphQLString;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import graphql.schema.GraphQLObjectType;
import static graphql.schema.GraphQLObjectType.newObject;
import graphql.schema.GraphQLSchema;

public class GraphQLFunction {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final WebClient webClient = WebClient.create("http://localhost:8081/api/appointments");

  private final GraphQL graphQL;

  public GraphQLFunction() {
    // Definición del tipo Appointment
    GraphQLObjectType appointmentType = newObject()
        .name("Appointment")
        .field(newFieldDefinition().name("clientName").type(GraphQLString))
        .field(newFieldDefinition().name("petName").type(GraphQLString))
        .field(newFieldDefinition().name("reason").type(GraphQLString))
        .field(newFieldDefinition().name("appointmentDate").type(GraphQLString))
        .build();

    // Definición del tipo Query
    GraphQLObjectType queryType = newObject()
        .name("Query")
        .field(newFieldDefinition()
            .name("getAppointments")
            .type(list(appointmentType)))
        .build();

    GraphQLCodeRegistry codeRegistry = GraphQLCodeRegistry.newCodeRegistry()
        .dataFetcher(
            FieldCoordinates.coordinates("Query", "getAppointments"),
            (DataFetcher<?>) env -> {
              String body = webClient.get()
                  .retrieve()
                  .bodyToMono(String.class)
                  .onErrorReturn("[]")
                  .block();

              return objectMapper.readValue(body, java.util.List.class);
            })
        .build();

    GraphQLSchema schema = GraphQLSchema.newSchema()
        .query(queryType)
        .codeRegistry(codeRegistry)
        .build();

    this.graphQL = GraphQL.newGraphQL(schema).build();
  }

  @FunctionName("GraphQLFunction")
  public HttpResponseMessage run(
      @HttpTrigger(name = "req", methods = {
          HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "graphql") HttpRequestMessage<Optional<String>> request,
      ExecutionContext context) {

    try {
      String body = request.getBody().orElse("");
      Map<String, Object> bodyMap = objectMapper.readValue(body, Map.class);

      ExecutionInput executionInput = ExecutionInput.newExecutionInput()
          .query((String) bodyMap.get("query"))
          .variables((Map<String, Object>) bodyMap.getOrDefault("variables", Map.of()))
          .build();

      ExecutionResult executionResult = graphQL.execute(executionInput);
      Map<String, Object> result = executionResult.toSpecification();

      return request.createResponseBuilder(HttpStatus.OK)
          .header("Content-Type", "application/json")
          .body(objectMapper.writeValueAsString(result))
          .build();

    } catch (Exception e) {
      context.getLogger().severe("❌ Error ejecutando GraphQL: " + e.getMessage());
      return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Error procesando GraphQL: " + e.getMessage())
          .build();
    }
  }
}
