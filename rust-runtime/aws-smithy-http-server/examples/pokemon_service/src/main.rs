/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

// This program is exported as a binary named `pokemon_service`.
use std::{net::SocketAddr, sync::Arc};

use aws_smithy_http_server::{AddExtensionLayer, Router};
use clap::Parser;
use pokemon_service::{
    capture_pokemon, empty_operation, get_pokemon_species, get_server_statistics, setup_tracing, State,
};
use pokemon_service_sdk::operation_registry::OperationRegistryBuilder;
use test_layer::TestLayer;
use tower::ServiceBuilder;
use tower_http::trace::TraceLayer;

mod test_layer;

#[derive(Parser, Debug)]
#[clap(author, version, about, long_about = None)]
struct Args {
    /// Hyper server bind address.
    #[clap(short, long, action, default_value = "127.0.0.1")]
    address: String,
    /// Hyper server bind port.
    #[clap(short, long, action, default_value = "13734")]
    port: u16,
}

#[tokio::main]
pub async fn main() {
    let args = Args::parse();
    setup_tracing();
    let app: Router = OperationRegistryBuilder::default()
        // Build a registry containing implementations to all the operations in the service. These
        // are async functions or async closures that take as input the operation's input and
        // return the operation's output.
        .get_pokemon_species(get_pokemon_species)
        .get_server_statistics(get_server_statistics)
        .capture_pokemon_operation(capture_pokemon)
        .empty_operation(empty_operation)
        .build()
        .expect("Unable to build operation registry")
        // Convert it into a router that will route requests to the matching operation
        // implementation.
        .into();

    let sdk_config = aws_config::load_from_env().await;

    // Setup shared state and middlewares.
    let shared_state = Arc::new(State::default());
    // If you have more than one layer, I recommend you always use `ServiceBuilder` to create
    // one big layer that you then tack on to the `Router` using `.layer()`, as opposed to calling
    // `.layer()` repeatedly.
    //
    // Axum has an _excellent_ guide explaining the rationale (that smithy-rs should copy into its
    // documentation; feel free to cut us an issue) and also introduces you to the "onion" mindset
    // you should have when reasoning about how your request's and response's flow through your
    // layers.
    //
    // Read this page first before reading my comments below:
    // https://docs.rs/axum/latest/axum/middleware/index.html#ordering
    let app = app.layer(
        ServiceBuilder::new()
            .layer(TraceLayer::new_for_http())
            .layer(TestLayer { sdk_config })
            .layer(AddExtensionLayer::new(shared_state)),
    );

    // Start the [`hyper::Server`].
    let bind: SocketAddr = format!("{}:{}", args.address, args.port)
        .parse()
        .expect("unable to parse the server bind address and port");
    let server = hyper::Server::bind(&bind).serve(app.into_make_service());

    // Run forever-ish...
    if let Err(err) = server.await {
        eprintln!("server error: {}", err);
    }
}
