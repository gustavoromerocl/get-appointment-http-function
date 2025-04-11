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

  // Para serializar/deserializar JSON
  private final ObjectMapper objectMapper = new ObjectMapper();

  // Cliente HTTP para consumir el microservicio de citas
  private final WebClient webClient = WebClient.create("http://localhost:8081/api/appointments");

  // Objeto principal de ejecución de GraphQL
  private final GraphQL graphQL;

  public GraphQLFunction() {
    // Definición del tipo "Appointment" que usará GraphQL
    GraphQLObjectType appointmentType = newObject()
        .name("Appointment")
        .field(newFieldDefinition().name("clientName").type(GraphQLString))
        .field(newFieldDefinition().name("petName").type(GraphQLString))
        .field(newFieldDefinition().name("reason").type(GraphQLString))
        .field(newFieldDefinition().name("appointmentDate").type(GraphQLString))
        .build();

    // Definición de la "Query"
    GraphQLObjectType queryType = newObject()
        .name("Query")
        .field(newFieldDefinition()
            .name("getAppointments")
            .type(list(appointmentType))) // devuelve una lista de Appointment
        .build();

    // Enlace entre el nombre del campo y la función que trae los datos
    GraphQLCodeRegistry codeRegistry = GraphQLCodeRegistry.newCodeRegistry()
        .dataFetcher(
            FieldCoordinates.coordinates("Query", "getAppointments"),
            (DataFetcher<?>) env -> {
              // Llamada al microservicio para obtener las citas
              String body = webClient.get()
                  .retrieve()
                  .bodyToMono(String.class)
                  .onErrorReturn("[]") // si falla, responde un array vacío
                  .block();

              // Deserializa JSON como lista genérica
              return objectMapper.readValue(body, java.util.List.class);
            })
        .build();

    // Construcción del esquema con Query y dataFetchers
    GraphQLSchema schema = GraphQLSchema.newSchema()
        .query(queryType)
        .codeRegistry(codeRegistry)
        .build();

    // Inicializa el motor GraphQL con el esquema
    this.graphQL = GraphQL.newGraphQL(schema).build();
  }

  @FunctionName("GraphQLFunction")
  public HttpResponseMessage run(
      @HttpTrigger(name = "req", methods = {
          HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS, route = "graphql") HttpRequestMessage<Optional<String>> request,
      ExecutionContext context) {

    try {
      // Extraer el cuerpo del request y convertir a mapa
      String body = request.getBody().orElse("");
      Map<String, Object> bodyMap = objectMapper.readValue(body, Map.class);

      // Preparar ejecución de la consulta con sus variables (si las hay)
      ExecutionInput executionInput = ExecutionInput.newExecutionInput()
          .query((String) bodyMap.get("query"))
          .variables((Map<String, Object>) bodyMap.getOrDefault("variables", Map.of()))
          .build();

      // Ejecutar consulta GraphQL
      ExecutionResult executionResult = graphQL.execute(executionInput);

      // Convertir resultado a formato estándar GraphQL
      Map<String, Object> result = executionResult.toSpecification();

      // Retornar respuesta HTTP con el resultado JSON
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
