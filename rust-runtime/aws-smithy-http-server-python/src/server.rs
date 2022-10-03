/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
// Code generated by software.amazon.smithy.rust.codegen.smithy-rs. DO NOT EDIT.

use std::{collections::HashMap, ops::Deref, process, thread};

use aws_smithy_http_server::{routing::Router, AddExtensionLayer};
use parking_lot::Mutex;
use pyo3::{prelude::*, types::IntoPyDict};
use signal_hook::{consts::*, iterator::Signals};
use tokio::runtime;
use tower::ServiceBuilder;

use crate::{middleware::PyMiddlewareHandler, PyMiddlewareType, PyMiddlewares, PySocket};

/// A Python handler function representation.
///
/// The Python business logic implementation needs to carry some information
/// to be executed properly like the size of its arguments and if it is
/// a coroutine.
#[pyclass]
#[derive(Debug, Clone)]
pub struct PyHandler {
    pub func: PyObject,
    pub args: usize,
    pub is_coroutine: bool,
}

impl Deref for PyHandler {
    type Target = PyObject;

    fn deref(&self) -> &Self::Target {
        &self.func
    }
}

/// Trait defining a Python application.
///
/// A Python application requires handling of multiple processes, signals and allows to register Python
/// function that will be executed as business logic by the code generated Rust handlers.
/// To properly function, the application requires some state:
/// * `workers`: the list of child Python worker processes, protected by a Mutex.
/// * `context`: the optional Python object that should be passed inside the Rust state struct.
/// * `handlers`: the mapping between an operation name and its [PyHandler] representation.
///
/// Since the Python application is spawning multiple workers, it also requires signal handling to allow the gracefull
/// termination of multiple Hyper servers. The main Rust process is registering signal and using them to understand when it
/// it time to loop through all the active workers and terminate them. Workers registers their own signal handlers and attaches
/// them to the Python event loop, ensuring all coroutines are cancelled before terminating a worker.
///
/// This trait will be implemented by the code generated by the `PythonApplicationGenerator` Kotlin class.
pub trait PyApp: Clone + pyo3::IntoPy<PyObject> {
    /// List of active Python workers registered with this application.
    fn workers(&self) -> &Mutex<Vec<PyObject>>;

    /// Optional Python context object that will be passed as part of the Rust state.
    fn context(&self) -> &Option<PyObject>;

    /// Mapping between operation names and their `PyHandler` representation.
    fn handlers(&mut self) -> &mut HashMap<String, PyHandler>;

    fn middlewares(&mut self) -> &mut PyMiddlewares;

    fn protocol(&self) -> &'static str;

    /// Handle the graceful termination of Python workers by looping through all the
    /// active workers and calling `terminate()` on them. If termination fails, this
    /// method will try to `kill()` any failed worker.
    fn graceful_termination(&self, workers: &Mutex<Vec<PyObject>>) -> ! {
        let workers = workers.lock();
        for (idx, worker) in workers.iter().enumerate() {
            let idx = idx + 1;
            Python::with_gil(|py| {
                let pid: isize = worker
                    .getattr(py, "pid")
                    .map(|pid| pid.extract(py).unwrap_or(-1))
                    .unwrap_or(-1);
                tracing::debug!("Terminating worker {idx}, PID: {pid}");
                match worker.call_method0(py, "terminate") {
                    Ok(_) => {}
                    Err(e) => {
                        tracing::error!("Error terminating worker {idx}, PID: {pid}: {e}");
                        worker
                            .call_method0(py, "kill")
                            .map_err(|e| {
                                tracing::error!(
                                    "Unable to kill kill worker {idx}, PID: {pid}: {e}"
                                );
                            })
                            .unwrap();
                    }
                }
            });
        }
        process::exit(0);
    }

    /// Handler the immediate termination of Python workers by looping through all the
    /// active workers and calling `kill()` on them.
    fn immediate_termination(&self, workers: &Mutex<Vec<PyObject>>) -> ! {
        let workers = workers.lock();
        for (idx, worker) in workers.iter().enumerate() {
            let idx = idx + 1;
            Python::with_gil(|py| {
                let pid: isize = worker
                    .getattr(py, "pid")
                    .map(|pid| pid.extract(py).unwrap_or(-1))
                    .unwrap_or(-1);
                tracing::debug!("Killing worker {idx}, PID: {pid}");
                worker
                    .call_method0(py, "kill")
                    .map_err(|e| {
                        tracing::error!("Unable to kill kill worker {idx}, PID: {pid}: {e}");
                    })
                    .unwrap();
            });
        }
        process::exit(0);
    }

    /// Register and handler signals of the main Rust thread. Signals not registered
    /// in this method are ignored.
    ///
    /// Signals supported:
    ///   * SIGTERM|SIGQUIT - graceful termination of all workers.
    ///   * SIGINT - immediate termination of all workers.
    ///
    /// Other signals are NOOP.
    fn block_on_rust_signals(&self) {
        let mut signals =
            Signals::new(&[SIGINT, SIGHUP, SIGQUIT, SIGTERM, SIGUSR1, SIGUSR2, SIGWINCH])
                .expect("Unable to register signals");
        for sig in signals.forever() {
            match sig {
                SIGINT => {
                    tracing::info!(
                        "Termination signal {sig:?} received, all workers will be immediately terminated"
                    );

                    self.immediate_termination(self.workers());
                }
                SIGTERM | SIGQUIT => {
                    tracing::info!(
                        "Termination signal {sig:?} received, all workers will be gracefully terminated"
                    );
                    self.graceful_termination(self.workers());
                }
                _ => {
                    tracing::debug!("Signal {sig:?} is ignored by this application");
                }
            }
        }
    }

    /// Register and handle termination of all the tasks on the Python asynchronous event loop.
    /// We only register SIGQUIT and SIGINT since the main signal handling is done by Rust.
    fn register_python_signals(&self, py: Python, event_loop: PyObject) -> PyResult<()> {
        let locals = [("event_loop", event_loop)].into_py_dict(py);
        py.run(
            r#"
import asyncio
import logging
import functools
import signal

async def shutdown(sig, event_loop):
    # reimport asyncio and logging to be sure they are available when
    # this handler runs on signal catching.
    import asyncio
    import logging
    logging.info(f"Caught signal {sig.name}, cancelling tasks registered on this loop")
    tasks = [task for task in asyncio.all_tasks() if task is not
             asyncio.current_task()]
    list(map(lambda task: task.cancel(), tasks))
    results = await asyncio.gather(*tasks, return_exceptions=True)
    logging.debug(f"Finished awaiting cancelled tasks, results: {results}")
    event_loop.stop()

event_loop.add_signal_handler(signal.SIGTERM,
    functools.partial(asyncio.ensure_future, shutdown(signal.SIGTERM, event_loop)))
event_loop.add_signal_handler(signal.SIGINT,
    functools.partial(asyncio.ensure_future, shutdown(signal.SIGINT, event_loop)))
"#,
            None,
            Some(locals),
        )?;
        Ok(())
    }

    /// Start a single worker with its own Tokio and Python async runtime and provided shared socket.
    ///
    /// Python asynchronous loop needs to be started and handled during the lifetime of the process and
    /// it is passed to this method by the caller, which can use
    /// [configure_python_event_loop](#method.configure_python_event_loop) to properly setup it up.
    ///
    /// We retrieve the Python context object, if setup by the user calling [PyApp::context] method,
    /// generate the state structure and build the [aws_smithy_http_server::routing::Router], filling
    /// it with the functions generated by `PythonServerOperationHandlerGenerator.kt`.
    /// At last we get a cloned reference to the underlying [socket2::Socket].
    ///
    /// Now that all the setup is done, we can start the two runtimes and run the [hyper] server.
    /// We spawn a thread with a new [tokio::runtime], setup the middlewares and finally block the
    /// thread on Hyper serve() method.
    /// The main process continues and at the end it is blocked on Python `loop.run_forever()`.
    ///
    /// [uvloop]: https://github.com/MagicStack/uvloop
    fn start_hyper_worker(
        &mut self,
        py: Python,
        socket: &PyCell<PySocket>,
        event_loop: &PyAny,
        router: Router,
        worker_number: isize,
    ) -> PyResult<()> {
        // Create the `PyState` object from the Python context object.
        let context = self.context().clone().unwrap_or_else(|| py.None());
        // let state = PyState::new(context);
        // Clone the socket.
        let borrow = socket.try_borrow_mut()?;
        let held_socket: &PySocket = &*borrow;
        let raw_socket = held_socket.get_socket()?;
        // Register signals on the Python event loop.
        self.register_python_signals(py, event_loop.to_object(py))?;

        // Spawn a new background [std::thread] to run the application.
        tracing::debug!("Start the Tokio runtime in a background task");
        thread::spawn(move || {
            // The thread needs a new [tokio] runtime.
            let rt = runtime::Builder::new_multi_thread()
                .enable_all()
                .thread_name(format!("smithy-rs-tokio[{worker_number}]"))
                .build()
                .expect("Unable to start a new tokio runtime for this process");
            // Register operations into a Router, add middleware and start the `hyper` server,
            // all inside a [tokio] blocking function.
            rt.block_on(async move {
                tracing::debug!("Add middlewares to Rust Python router");
                let app =
                    router.layer(ServiceBuilder::new().layer(AddExtensionLayer::new(context)));
                let server = hyper::Server::from_tcp(
                    raw_socket
                        .try_into()
                        .expect("Unable to convert socket2::Socket into std::net::TcpListener"),
                )
                .expect("Unable to create hyper server from shared socket")
                .serve(app.into_make_service());

                tracing::debug!("Started hyper server from shared socket");
                // Run forever-ish...
                if let Err(err) = server.await {
                    tracing::error!("server error: {}", err);
                }
            });
        });
        // Block on the event loop forever.
        tracing::debug!("Run and block on the Python event loop until a signal is received");
        event_loop.call_method0("run_forever")?;
        Ok(())
    }

    // Check if a Python function is a coroutine. Since the function has not run yet,
    // we cannot use `asyncio.iscoroutine()`, we need to use `inspect.iscoroutinefunction()`.
    fn is_coroutine(&self, py: Python, func: &PyObject) -> PyResult<bool> {
        let inspect = py.import("inspect")?;
        // NOTE: that `asyncio.iscoroutine()` doesn't work here.
        inspect
            .call_method1("iscoroutinefunction", (func,))?
            .extract::<bool>()
    }

    /// Register a Python function to be executed inside a Tower middleware layer.
    ///
    /// There are some information needed to execute the Python code from a Rust handler,
    /// such has if the registered function needs to be awaited (if it is a coroutine)..
    fn register_middleware(
        &mut self,
        py: Python,
        func: PyObject,
        _type: PyMiddlewareType,
    ) -> PyResult<()> {
        let name = func.getattr(py, "__name__")?.extract::<String>(py)?;
        let is_coroutine = self.is_coroutine(py, &func)?;
        // Find number of expected methods (a Python implementation could not accept the context).
        let handler = PyMiddlewareHandler {
            name,
            func,
            is_coroutine,
            _type,
        };
        tracing::info!(
            "Registering middleware function `{}`, coroutine: {}, type: {:?}",
            handler.name,
            handler.is_coroutine,
            handler._type
        );
        self.middlewares().push(handler);
        Ok(())
    }

    /// Register a Python function to be executed inside the Smithy Rust handler.
    ///
    /// There are some information needed to execute the Python code from a Rust handler,
    /// such has if the registered function needs to be awaited (if it is a coroutine) and
    /// the number of arguments available, which tells us if the handler wants the state to be
    /// passed or not.
    fn register_operation(&mut self, py: Python, name: &str, func: PyObject) -> PyResult<()> {
        let is_coroutine = self.is_coroutine(py, &func)?;
        // Find number of expected methods (a Python implementation could not accept the context).
        let inspect = py.import("inspect")?;
        let func_args = inspect
            .call_method1("getargs", (func.getattr(py, "__code__")?,))?
            .getattr("args")?
            .extract::<Vec<String>>()?;
        let handler = PyHandler {
            func,
            is_coroutine,
            args: func_args.len(),
        };
        tracing::info!(
            "Registering handler function `{name}`, coroutine: {}, arguments: {}",
            handler.is_coroutine,
            handler.args,
        );
        // Insert the handler in the handlers map.
        self.handlers().insert(name.to_string(), handler);
        Ok(())
    }

    /// Configure the Python asyncio event loop.
    ///
    /// First of all we install [uvloop] as the main Python event loop. Thanks to libuv, uvloop
    /// performs ~20% better than Python standard event loop in most benchmarks, while being 100%
    /// compatible. If [uvloop] is not available as a dependency, we just fall back to the standard
    /// Python event loop.
    ///
    /// [uvloop]: https://github.com/MagicStack/uvloop
    fn configure_python_event_loop<'py>(&self, py: Python<'py>) -> PyResult<&'py PyAny> {
        let asyncio = py.import("asyncio")?;
        match py.import("uvloop") {
            Ok(uvloop) => {
                uvloop.call_method0("install")?;
                tracing::debug!("Setting up uvloop for current process");
            }
            Err(_) => {
                tracing::warn!("Uvloop not found, using Python standard event loop, which could have worse performance than uvloop");
            }
        }
        let event_loop = asyncio.call_method0("new_event_loop")?;
        asyncio.call_method1("set_event_loop", (event_loop,))?;
        Ok(event_loop)
    }

    /// Main entrypoint: start the server on multiple workers.
    ///
    /// The multiprocessing server is achieved using the ability of a Python interpreter
    /// to clone and start itself as a new process.
    /// The shared sockets is created and Using the [multiprocessing::Process] module, multiple
    /// workers with the method `self.start_worker()` as target are started.
    ///
    /// NOTE: this method ends up calling `self.start_worker` from the Python context, forcing
    /// the struct implementing this trait to also implement a `start_worker` method.
    /// This is done to ensure the Python event loop is started in the right child process space before being
    /// passed to `start_hyper_worker`.
    ///
    /// `PythonApplicationGenerator.kt` generates the `start_worker` method:
    ///
    /// ```no_run
    ///     use std::collections::HashMap;
    ///     use pyo3::prelude::*;
    ///     use aws_smithy_http_server_python::{PyApp, PyHandler, PyMiddlewares};
    ///     use parking_lot::Mutex;
    ///
    ///     #[pyclass]
    ///     #[derive(Debug, Clone)]
    ///     pub struct App {};
    ///
    ///     impl App {
    ///         pub fn build_router(&mut self, event_loop: &PyAny) -> PyResult<aws_smithy_http_server::routing::Router> { todo!() }
    ///     }
    ///
    ///     impl PyApp for App {
    ///         fn workers(&self) -> &Mutex<Vec<PyObject>> { todo!() }
    ///         fn context(&self) -> &Option<PyObject> { todo!() }
    ///         fn handlers(&mut self) -> &mut HashMap<String, PyHandler> { todo!() }
    ///         fn middlewares(&mut self) -> &mut PyMiddlewares { todo!() }
    ///         fn protocol(&self) -> &'static str { "proto1" }
    ///     }
    ///
    ///     #[pymethods]
    ///     impl App {
    ///     #[pyo3(text_signature = "($self, socket, worker_number)")]
    ///         pub fn start_worker(
    ///             &mut self,
    ///             py: pyo3::Python,
    ///             socket: &pyo3::PyCell<aws_smithy_http_server_python::PySocket>,
    ///             worker_number: isize,
    ///         ) -> pyo3::PyResult<()> {
    ///             let event_loop = self.configure_python_event_loop(py)?;
    ///             let router = self.build_router(event_loop)?;
    ///             self.start_hyper_worker(py, socket, event_loop, router, worker_number)
    ///         }
    ///     }
    /// ```
    ///
    /// [multiprocessing::Process]: https://docs.python.org/3/library/multiprocessing.html
    fn run_server(
        &mut self,
        py: Python,
        address: Option<String>,
        port: Option<i32>,
        backlog: Option<i32>,
        workers: Option<usize>,
    ) -> PyResult<()> {
        // Setup multiprocessing environment, allowing connections and socket
        // sharing between processes.
        let mp = py.import("multiprocessing")?;
        // https://github.com/python/cpython/blob/f4c03484da59049eb62a9bf7777b963e2267d187/Lib/multiprocessing/context.py#L164
        mp.call_method0("allow_connection_pickling")?;

        // Starting from Python 3.8, on macOS, the spawn start method is now the default. See bpo-33725.
        // This forces the `PyApp` class to be pickled when it is shared between different process,
        // which is currently not supported by PyO3 classes.
        //
        // Forcing the multiprocessing start method to fork is a workaround for it.
        // https://github.com/pytest-dev/pytest-flask/issues/104#issuecomment-577908228
        #[cfg(target_os = "macos")]
        mp.call_method1("set_start_method", ("fork",))?;

        let address = address.unwrap_or_else(|| String::from("127.0.0.1"));
        let port = port.unwrap_or(13734);
        let socket = PySocket::new(address, port, backlog)?;
        // Lock the workers mutex.
        let mut active_workers = self.workers().lock();
        // Register the main signal handler.
        // TODO(move from num_cpus to thread::available_parallelism after MSRV is 1.60)
        // Start all the workers as new Python processes and store the in the `workers` attribute.
        for idx in 1..workers.unwrap_or_else(num_cpus::get) + 1 {
            let sock = socket.try_clone()?;
            let process = mp.getattr("Process")?;
            let handle = process.call1((
                py.None(),
                self.clone().into_py(py).getattr(py, "start_worker")?,
                format!("smithy-rs-worker[{idx}]"),
                (sock.into_py(py), idx),
            ))?;
            handle.call_method0("start")?;
            active_workers.push(handle.to_object(py));
        }
        // Unlock the workers mutex.
        drop(active_workers);
        tracing::info!("Rust Python server started successfully");
        self.block_on_rust_signals();
        Ok(())
    }
}