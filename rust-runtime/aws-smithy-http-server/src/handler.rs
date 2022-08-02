/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::{
    convert::Infallible,
    future::Future,
    marker::PhantomData,
    task::{Context, Poll},
};

use futures_util::{future::Map, FutureExt};
use tower::{util::BoxService, Service};

pub trait Handler<T, B> {
    type Future: Future<Output = http::Response<B>>;

    fn call(&self, request: http::Request<B>) -> Self::Future;
}

pub trait FromRequest<B> {
    fn from_request(request: http::Request<B>) -> Self;
}

pub trait IntoResponse<B> {
    fn into_response(self) -> http::Response<B>;
}

impl<T, B, F, Fut> Handler<T, B> for F
where
    F: Fn(T) -> Fut,
    Fut: Future,
    T: FromRequest<B>,
    Fut::Output: IntoResponse<B>,
{
    type Future = Map<Fut, fn(Fut::Output) -> http::Response<B>>;

    fn call(&self, request: http::Request<B>) -> Self::Future {
        let t1 = T::from_request(request);
        (self)(t1).map(<Fut::Output>::into_response)
    }
}

pub trait MakeStateful {
    fn with_state<S>(self, state: S) -> Stateful<Self, S>
    where
        Self: Sized,
    {
        Stateful { closure: self, state }
    }
}

impl<F> MakeStateful for F {}

pub struct Stateful<F, S> {
    closure: F,
    state: S,
}

impl<T, S, B, F, Fut> Handler<T, B> for Stateful<F, S>
where
    F: Fn(T, S) -> Fut,
    S: Clone,
    Fut: Future,
    T: FromRequest<B>,
    Fut::Output: IntoResponse<B>,
{
    type Future = Map<Fut, fn(Fut::Output) -> http::Response<B>>;

    fn call(&self, request: http::Request<B>) -> Self::Future {
        let t = T::from_request(request);
        let Stateful { closure, state, .. } = self;
        (closure)(t, state.clone()).map(<Fut::Output>::into_response)
    }
}

pub struct HandlerService<H, T> {
    handler: H,
    _args: PhantomData<T>,
}

impl<B, H, T> Service<http::Request<B>> for HandlerService<H, T>
where
    H: Handler<T, B>,
{
    type Response = http::Response<B>;
    type Error = Infallible;
    type Future = Map<H::Future, fn(http::Response<B>) -> Result<http::Response<B>, Infallible>>;

    fn poll_ready(&mut self, _cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<B>) -> Self::Future {
        self.handler.call(request).map(Ok)
    }
}

pub trait HandlerExt<T, B>: Handler<T, B> {
    fn into_service(self) -> HandlerService<Self, T>
    where
        Self: Sized,
    {
        HandlerService {
            handler: self,
            _args: PhantomData,
        }
    }
}

impl<T, B, H> HandlerExt<T, B> for H where H: Handler<T, B> {}

mod example_generated {
    use std::marker::PhantomData;

    use tower::Layer;

    use super::*;

    struct ServiceExample<S> {
        router: Vec<S>,
    }

    impl ServiceExample<()> {
        pub fn builder<B>() -> ServiceExampleBuilder<(), (), B> {
            ServiceExampleBuilder {
                op1: (),
                op2: (),
                _body: PhantomData,
            }
        }
    }

    struct ServiceExampleBuilder<Op1, Op2, B> {
        op1: Op1,
        op2: Op2,
        _body: PhantomData<B>,
    }

    struct Input1;
    impl<B> FromRequest<B> for Input1 {
        fn from_request(request: http::Request<B>) -> Self {
            Self
        }
    }

    struct Output1;
    impl<B> IntoResponse<B> for Output1 {
        fn into_response(self) -> http::Response<B> {
            todo!()
        }
    }

    struct Input2;
    impl<B> FromRequest<B> for Input2 {
        fn from_request(request: http::Request<B>) -> Self {
            Self
        }
    }

    struct Output2;
    impl<B> IntoResponse<B> for Output2 {
        fn into_response(self) -> http::Response<B> {
            todo!()
        }
    }

    impl<Op1, Op2, B> ServiceExampleBuilder<Op1, Op2, B> {
        pub fn operation_1<H>(self, handler: H) -> ServiceExampleBuilder<HandlerService<H, Input1>, Op2, B>
        where
            H: Handler<Input1, B>,
        {
            ServiceExampleBuilder {
                op1: handler.into_service(),
                op2: self.op2,
                _body: PhantomData,
            }
        }

        pub fn operation_1_layer<L>(self, layer: &L) -> ServiceExampleBuilder<L::Service, Op2, B>
        where
            L: Layer<Op1>,
        {
            ServiceExampleBuilder {
                op1: layer.layer(self.op1),
                op2: self.op2,
                _body: PhantomData,
            }
        }

        pub fn operation_2<H>(self, handler: H) -> ServiceExampleBuilder<Op1, HandlerService<H, Input2>, B>
        where
            H: Handler<Input2, B>,
        {
            ServiceExampleBuilder {
                op1: self.op1,
                op2: handler.into_service(),
                _body: PhantomData,
            }
        }

        pub fn operation_2_layer<L>(self, layer: &L) -> ServiceExampleBuilder<Op1, L::Service, B>
        where
            L: Layer<Op2>,
        {
            ServiceExampleBuilder {
                op1: self.op1,
                op2: layer.layer(self.op2),
                _body: PhantomData,
            }
        }

        pub fn layer_all<L, F>(
            self,
            f: F,
        ) -> ServiceExampleBuilder<<L as Layer<Op1>>::Service, <L as Layer<Op2>>::Service, B>
        where
            F: Fn(&'static str) -> L,
            L: Layer<Op1>,
            L: Layer<Op2>,
        {
            ServiceExampleBuilder {
                op1: (f)("operation_1").layer(self.op1),
                op2: (f)("operation_2").layer(self.op2),
                _body: PhantomData,
            }
        }
    }

    impl<Op1, Op2, B> ServiceExampleBuilder<Op1, Op2, B> {
        fn build(self) -> ServiceExample<BoxService<http::Request<B>, http::Response<B>, Infallible>>
        where
            Op1: Service<http::Request<B>, Response = http::Response<B>, Error = Infallible> + Send + 'static,
            Op1::Future: Send + 'static,
            Op2: Service<http::Request<B>, Response = http::Response<B>, Error = Infallible> + Send + 'static,
            Op2::Future: Send + 'static,
        {
            ServiceExample {
                router: vec![BoxService::new(self.op1), BoxService::new(self.op2)],
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        async fn operation_1(input: Input1) -> Output1 {
            Output1
        }

        #[derive(Clone)]
        struct State;
        struct Body;

        async fn operation_2(input: Input2, state: State) -> Output2 {
            Output2
        }

        #[test]
        fn compose() {
            let service = ServiceExample::builder::<Body>()
                .operation_1(operation_1)
                .operation_2(operation_2.with_state(State))
                .build();
        }
    }
}
