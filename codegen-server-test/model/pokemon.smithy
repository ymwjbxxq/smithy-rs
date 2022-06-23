$version: "1.0"

namespace com.aws.example

use aws.protocols#restJson1

/// [service-docs] The Pokémon Service allows you to retrieve information about Pokémon species.
@title("Pokémon Service")
@restJson1
service PokemonService {
    /// [service-members-docs] API version.
    version: "2021-12-01",
    /// [service-members-docs] Exposed resources.
    resources: [PokemonSpecies],
    /// [service-members-docs] Exposed operations.
    operations: [GetServerStatistics, EmptyOperation],
}

/// [resource-docs] A Pokémon species forms the basis for at least one Pokémon.
@title("Pokémon Species")
resource PokemonSpecies {
    /// [resource-members-docs] The Pokémon's name.
    identifiers: {
        name: String
    },
    /// [resource-members-docs] Read operation for this resource.
    read: GetPokemonSpecies,
}

/// [operation-docs] Retrieve information about a Pokémon species.
@readonly
@http(uri: "/pokemon-species/{name}", method: "GET")
operation GetPokemonSpecies {
    /// [operation-members-docs] Input structure for GetPokemonSpecies operation.
    input: GetPokemonSpeciesInput,
    /// [operation-members-docs] Output structure for GetPokemonSpecies operation.
    output: GetPokemonSpeciesOutput,
    /// [operation-members-docs] Errors that GetPokemonSpecies operation can return.
    errors: [ResourceNotFoundException],
}

/// [structure-docs] Input structure for GetPokemonSpecies operation
@input
structure GetPokemonSpeciesInput {
    /// [structure-members-docs] The Pokémon's name.
    @required
    @httpLabel
    name: String
}

/// [structure-docs] Output structure for GetPokemonSpecies operation
@output
structure GetPokemonSpeciesOutput {
    /// [structure-members-docs] The name for this resource.
    @required
    name: String,

    /// [structure-members-docs] A list of flavor text entries for this Pokémon species.
    @required
    flavorTextEntries: FlavorTextEntries,

    @required
    flavorTextEntriesSet: FlavorTextEntriesSet,

    @required
    flavorTextMap: AMap,

    @required
    daUnion: MyUnion
}

/// [union-docs] MyUnion.
union MyUnion {
    /// [union-members-docs] a i32 in the union.
    i32: Integer,
    /// [union-members-docs] a time in the union.
    time: Timestamp,
}

/// [operation-docs] Retrieve HTTP server statistiscs, such as calls count.
@readonly
@http(uri: "/stats", method: "GET")
operation GetServerStatistics {
    /// [operation-members-docs] Input structure for GetServerStatistics operation.
    input: GetServerStatisticsInput,
    /// [operation-members-docs] Output structure for GetServerStatistics operation.
    output: GetServerStatisticsOutput,
}

/// [structure-docs] Input structure for GetServerStatistics operation.
@input
structure GetServerStatisticsInput { }

/// [structure-docs] Output structure for GetServerStatistics operation.
@output
structure GetServerStatisticsOutput {
    /// [structure-members-docs] The number of calls executed by the server.
    @required
    calls_count: Long,
}

/// [list-docs] List of FlavorText.
list FlavorTextEntries {
    /// [list-members-docs] Type of the FlavorTextEntries list.
    member: FlavorText
}

/// [set-docs] List of FlavorText.
set FlavorTextEntriesSet {
    /// [set-members-docs] Type of the FlavorTextEntries list.
    member: FlavorText
}

/// [structure-docs] Localized flavor text for an API resource in a specific language.
structure FlavorText {
    /// [structure-members-docs] The localized flavor text for an API resource in a specific language.
    @required
    flavorText: String,

    /// [structure-members-docs] The language this name is in.
    @required
    language: Language,
}

/// [enum-docs] Supported languages for FlavorText entries.
@enum([
    {
        name: "ENGLISH",
        value: "en",
        documentation: "[enum-members-docs] American English.",
    },
    {
        name: "SPANISH",
        value: "es",
        documentation: "[enum-members-docs] Español.",
    },
    {
        name: "ITALIAN",
        value: "it",
        documentation: "[enum-members-docs] Italiano.",
    },
])
string Language

/// [map-docs] A map docs.
map AMap {
    /// [map-members-docs] Map key.
    key: String,
    /// [map-members-docs] Map value.
    value: FlavorText,
}

/// [operation-docs] Empty operation, used to stress test the framework.
@readonly
@http(uri: "/empty-operation", method: "GET")
operation EmptyOperation {
    /// [operation-members-docs] Input structure for EmptyOperation operation.
    input: EmptyOperationInput,
    /// [operation-members-docs] Output structure for EmptyOperation operation.
    output: EmptyOperationOutput,
}

/// [structure-docs] Input structure for EmptyOperation operation.
@input
structure EmptyOperationInput { }

/// [structure-docs] Output structure for EmptyOperation operation.
@output
structure EmptyOperationOutput { }

/// [error-docs] Error used when a resource is not found.
@error("client")
@httpError(404)
structure ResourceNotFoundException {
    /// [error-members-docs] Error message.
    @required
    message: String,
}
