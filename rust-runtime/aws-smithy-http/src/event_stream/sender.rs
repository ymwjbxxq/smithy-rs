/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use super::BoxError;
use crate::result::SdkError;
use aws_smithy_eventstream::frame::{MarshallMessage, SignMessage};
use bytes::Bytes;
use futures_core::Stream;
use pin_project::pin_project;
use std::error::Error as StdError;
use std::fmt;
use std::fmt::Debug;
use std::pin::Pin;
use std::task::{Context, Poll};

/// Input type for Event Streams.
pub struct EventStreamSender<T> {
    input_stream: Pin<Box<dyn Stream<Item = Result<T, BoxError>> + Send>>,
}

impl<T> Debug for EventStreamSender<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "EventStreamSender(Box<dyn Stream>)")
    }
}

impl<T> EventStreamSender<T> {
    #[doc(hidden)]
    pub fn into_body_stream(
        self,
        marshaller: impl MarshallMessage<Input = T> + Send + Sync + 'static,
        signer: impl SignMessage + Send + Sync + 'static,
    ) -> MessageStreamAdapter<T> {
        MessageStreamAdapter::new(marshaller, signer, self.input_stream)
    }
}

impl<T, S> From<S> for EventStreamSender<T>
where
    S: Stream<Item = Result<T, BoxError>> + Send + 'static,
{
    fn from(stream: S) -> Self {
        EventStreamSender {
            input_stream: Box::pin(stream),
        }
    }
}

#[derive(Debug)]
pub struct MessageStreamError {
    kind: MessageStreamErrorKind,
    pub(crate) meta: aws_smithy_types::Error,
}

#[derive(Debug)]
pub enum MessageStreamErrorKind {
    Unhandled(Box<dyn std::error::Error + Send + Sync + 'static>),
}

impl MessageStreamError {
    /// Creates the `MessageStreamError::Unhandled` variant from any error type.
    pub fn unhandled(err: impl Into<Box<dyn std::error::Error + Send + Sync + 'static>>) -> Self {
        Self {
            meta: Default::default(),
            kind: MessageStreamErrorKind::Unhandled(err.into()),
        }
    }

    /// Creates the `MessageStreamError::Unhandled` variant from a `aws_smithy_types::Error`.
    pub fn generic(err: aws_smithy_types::Error) -> Self {
        Self {
            meta: err.clone(),
            kind: MessageStreamErrorKind::Unhandled(err.into()),
        }
    }

    /// Returns error metadata, which includes the error code, message,
    /// request ID, and potentially additional information.
    pub fn meta(&self) -> &aws_smithy_types::Error {
        &self.meta
    }
}

impl StdError for MessageStreamError {}
impl fmt::Display for MessageStreamError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.kind {
            MessageStreamErrorKind::Unhandled(inner) => std::fmt::Debug::fmt(inner, f),
        }
    }
}

/// Adapts a `Stream<SmithyMessageType>` to a signed `Stream<Bytes>` by using the provided
/// message marshaller and signer implementations.
///
/// This will yield an `Err(SdkError::ConstructionFailure)` if a message can't be
/// marshalled into an Event Stream frame, (e.g., if the message payload was too large).
#[pin_project]
pub struct MessageStreamAdapter<T> {
    marshaller: Box<dyn MarshallMessage<Input = T> + Send + Sync>,
    signer: Box<dyn SignMessage + Send + Sync>,
    #[pin]
    stream: Pin<Box<dyn Stream<Item = Result<T, BoxError>> + Send>>,
    end_signal_sent: bool,
}

impl<T> MessageStreamAdapter<T> {
    pub fn new(
        marshaller: impl MarshallMessage<Input = T> + Send + Sync + 'static,
        signer: impl SignMessage + Send + Sync + 'static,
        stream: Pin<Box<dyn Stream<Item = Result<T, BoxError>> + Send>>,
    ) -> Self {
        MessageStreamAdapter {
            marshaller: Box::new(marshaller),
            signer: Box::new(signer),
            stream,
            end_signal_sent: false,
        }
    }
}

impl<T> Stream for MessageStreamAdapter<T> {
    type Item = Result<Bytes, SdkError<MessageStreamError>>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = self.project();
        match this.stream.poll_next(cx) {
            Poll::Ready(message_option) => {
                if let Some(message_result) = message_option {
                    let message_result =
                        message_result.map_err(|err| SdkError::ConstructionFailure(err));
                    let message = this
                        .marshaller
                        .marshall(message_result?)
                        .map_err(|err| SdkError::ConstructionFailure(Box::new(err)))?;
                    let message = this
                        .signer
                        .sign(message)
                        .map_err(|err| SdkError::ConstructionFailure(err))?;
                    let mut buffer = Vec::new();
                    message
                        .write_to(&mut buffer)
                        .map_err(|err| SdkError::ConstructionFailure(Box::new(err)))?;
                    Poll::Ready(Some(Ok(Bytes::from(buffer))))
                } else if !*this.end_signal_sent {
                    *this.end_signal_sent = true;
                    let mut buffer = Vec::new();
                    match this.signer.sign_empty() {
                        Some(sign) => {
                            sign.map_err(|err| SdkError::ConstructionFailure(err))?
                                .write_to(&mut buffer)
                                .map_err(|err| SdkError::ConstructionFailure(Box::new(err)))?;
                            Poll::Ready(Some(Ok(Bytes::from(buffer))))
                        }
                        None => Poll::Ready(None),
                    }
                } else {
                    Poll::Ready(None)
                }
            }
            Poll::Pending => Poll::Pending,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::MarshallMessage;
    use crate::event_stream::{EventStreamSender, MessageStreamAdapter};
    use crate::result::SdkError;
    use async_stream::stream;
    use aws_smithy_eventstream::error::Error as EventStreamError;
    use aws_smithy_eventstream::frame::{
        Header, HeaderValue, Message, SignMessage, SignMessageError,
    };
    use bytes::Bytes;
    use futures_core::Stream;
    use futures_util::stream::StreamExt;
    use std::error::Error as StdError;

    #[derive(Debug)]
    struct FakeError;
    impl std::fmt::Display for FakeError {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "FakeError")
        }
    }
    impl StdError for FakeError {}

    #[derive(Debug, Eq, PartialEq)]
    struct TestMessage(String);

    #[derive(Debug)]
    struct Marshaller;
    impl MarshallMessage for Marshaller {
        type Input = TestMessage;

        fn marshall(&self, input: Self::Input) -> Result<Message, EventStreamError> {
            Ok(Message::new(input.0.as_bytes().to_vec()))
        }
    }

    #[derive(Debug)]
    struct TestServiceError;
    impl std::fmt::Display for TestServiceError {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "TestServiceError")
        }
    }
    impl StdError for TestServiceError {}

    #[derive(Debug)]
    struct TestSigner;
    impl SignMessage for TestSigner {
        fn sign(&mut self, message: Message) -> Result<Message, SignMessageError> {
            let mut buffer = Vec::new();
            message.write_to(&mut buffer).unwrap();
            Ok(Message::new(buffer).add_header(Header::new("signed", HeaderValue::Bool(true))))
        }

        fn sign_empty(&mut self) -> Result<Message, SignMessageError> {
            Ok(Message::new(&b""[..]).add_header(Header::new("signed", HeaderValue::Bool(true))))
        }
    }

    fn check_compatible_with_hyper_wrap_stream<S, O, E>(stream: S) -> S
    where
        S: Stream<Item = Result<O, E>> + Send + 'static,
        O: Into<Bytes> + 'static,
        E: Into<Box<dyn StdError + Send + Sync>> + 'static,
    {
        stream
    }

    #[tokio::test]
    async fn message_stream_adapter_success() {
        let stream = stream! {
            yield Ok(TestMessage("test".into()));
        };
        let mut adapter =
            check_compatible_with_hyper_wrap_stream(MessageStreamAdapter::<
                TestMessage,
                TestServiceError,
            >::new(
                Marshaller, TestSigner, Box::pin(stream)
            ));

        let mut sent_bytes = adapter.next().await.unwrap().unwrap();
        let sent = Message::read_from(&mut sent_bytes).unwrap();
        assert_eq!("signed", sent.headers()[0].name().as_str());
        assert_eq!(&HeaderValue::Bool(true), sent.headers()[0].value());
        let inner = Message::read_from(&mut (&sent.payload()[..])).unwrap();
        assert_eq!(&b"test"[..], &inner.payload()[..]);

        let mut end_signal_bytes = adapter.next().await.unwrap().unwrap();
        let end_signal = Message::read_from(&mut end_signal_bytes).unwrap();
        assert_eq!("signed", end_signal.headers()[0].name().as_str());
        assert_eq!(&HeaderValue::Bool(true), end_signal.headers()[0].value());
        assert_eq!(0, end_signal.payload().len());
    }

    #[tokio::test]
    async fn message_stream_adapter_construction_failure() {
        let stream = stream! {
            yield Err(EventStreamError::InvalidMessageLength.into());
        };
        let mut adapter =
            check_compatible_with_hyper_wrap_stream(MessageStreamAdapter::<
                TestMessage,
                TestServiceError,
            >::new(
                Marshaller, TestSigner, Box::pin(stream)
            ));

        let result = adapter.next().await.unwrap();
        assert!(result.is_err());
        assert!(matches!(
            result.err().unwrap(),
            SdkError::ConstructionFailure(_)
        ));
    }

    // Verify the developer experience for this compiles
    #[allow(unused)]
    fn event_stream_input_ergonomics() {
        fn check(input: impl Into<EventStreamSender<TestMessage>>) {
            let _: EventStreamSender<TestMessage> = input.into();
        }
        check(stream! {
            yield Ok(TestMessage("test".into()));
        });
        check(stream! {
            yield Err(EventStreamError::InvalidMessageLength.into());
        });
    }
}
