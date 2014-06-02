/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron;

import uk.co.real_logic.aeron.conductor.*;
import uk.co.real_logic.aeron.util.AtomicArray;
import uk.co.real_logic.aeron.util.CommonConfiguration;
import uk.co.real_logic.aeron.util.ConductorByteBuffers;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBuffer;

import java.nio.ByteBuffer;

import static uk.co.real_logic.aeron.util.concurrent.ringbuffer.BufferDescriptor.TRAILER_LENGTH;

/**
 * Encapsulation of media driver and API for source and receiver construction
 */
public final class Aeron implements AutoCloseable
{
    private static final int ADMIN_BUFFER_SIZE = 512 + TRAILER_LENGTH;

    private final ManyToOneRingBuffer mediaConductorCommandBuffer;
    private final ClientConductor clientConductor;
    private final ConductorByteBuffers adminBuffers;
    private final AtomicArray<Channel> channels;
    private final AtomicArray<SubscriberChannel> receivers;

    private Aeron(final Context context)
    {
        this.channels = new AtomicArray<>();
        this.receivers = new AtomicArray<>();
        this.mediaConductorCommandBuffer = new ManyToOneRingBuffer(new AtomicBuffer(ByteBuffer.allocate(ADMIN_BUFFER_SIZE)));

        if (null == context.conductorByteBuffers)
        {
            this.adminBuffers = new ConductorByteBuffers(CommonConfiguration.ADMIN_DIR_NAME);
        }
        else
        {
            this.adminBuffers = context.conductorByteBuffers;
        }

        try
        {
            final RingBuffer rcvBuffer = new ManyToOneRingBuffer(new AtomicBuffer(adminBuffers.toClient()));
            final RingBuffer sendBuffer = new ManyToOneRingBuffer(new AtomicBuffer(adminBuffers.toMediaDriver()));
            final BufferUsageStrategy bufferUsage = new MappingBufferUsageStrategy();
            final ConductorErrorHandler conductorErrorHandler =
                new ConductorErrorHandler(context.invalidDestinationHandler);

            this.clientConductor = new ClientConductor(mediaConductorCommandBuffer,
                                                       rcvBuffer, sendBuffer,
                                                       bufferUsage,
                                                       channels, receivers,
                                                       conductorErrorHandler,
                                                       context.publisherControlFactory);
        }
        catch (final Exception ex)
        {
            throw new IllegalArgumentException("Unable to create Aeron", ex);
        }
    }

    public void close()
    {
        adminBuffers.close();
        clientConductor.close();
    }

    /**
     * Creates an media driver associated with this Aeron instance that can be used to create sources and receivers on.
     *
     * @param context of the media driver and Aeron configuration or null for default configuration
     * @return Aeron instance
     */
    public static Aeron newSingleMediaDriver(final Context context)
    {
        return new Aeron(context);
    }

    /**
     * Creates multiple media drivers associated with multiple Aeron instances that can be used to create sources
     * and receivers.
     *
     * @param contexts of the media drivers
     * @return array of Aeron instances
     */
    public static Aeron[] newMultipleMediaDrivers(final Context[] contexts)
    {
        final Aeron[] aerons = new Aeron[contexts.length];

        for (int i = 0, max = contexts.length; i < max; i++)
        {
            aerons[i] = new Aeron(contexts[i]);
        }

        return aerons;
    }

    /**
     * Create a new source that is to send to {@link uk.co.real_logic.aeron.Destination}.
     * <p>
     * A unique, random, session ID will be generated for the source if the context does not
     * set it. If the context sets the Session ID, then it will be checked for conflicting with existing session Ids.
     *
     * @param context for source options, etc.
     * @return new source
     */
    public Source newSource(final Source.Context context)
    {
        context.mediaConductorProxy(new MediaConductorProxy(mediaConductorCommandBuffer));
        return new Source(channels, context);
    }

    /**
     * Create a new source that is to send to {@link Destination}
     *
     * @param destination address to send all data to
     * @return new source
     */
    public Source newSource(final Destination destination)
    {
        return newSource(new Source.Context().destination(destination));
    }

    /**
     * Create an array of sources.
     * <p>
     * Convenience function to make it easier to create a number of Sources easier.
     *
     * @param contexts for the source options, etc.
     * @return array of new sources.
     */
    public Source[] newSources(final Source.Context[] contexts)
    {
        final Source[] sources = new Source[contexts.length];

        for (int i = 0, max = contexts.length; i < max; i++)
        {
            sources[i] = newSource(contexts[i]);
        }

        return sources;
    }

    /**
     * Create a new receiver that will listen on {@link uk.co.real_logic.aeron.Destination}
     *
     * @param context context for receiver options.
     * @return new receiver
     */
    public Subscriber newSubscriber(final Subscriber.Context context)
    {
        final MediaConductorProxy mediaConductorProxy = new MediaConductorProxy(mediaConductorCommandBuffer);

        return new Subscriber(mediaConductorProxy, context, receivers);
    }

    /**
     * Create a new receiver that will listen on a given destination, etc.
     *
     * @param block to fill in receiver context
     * @return new Subscriber
     */
    public Subscriber newSubscriber(final java.util.function.Consumer<Subscriber.Context> block)
    {
        final Subscriber.Context context = new Subscriber.Context();
        block.accept(new Subscriber.Context());

        return newSubscriber(context);
    }

    public ClientConductor conductor()
    {
        return clientConductor;
    }

    public static class Context
    {
        private ErrorHandler errorHandler = new DummyErrorHandler();
        private ConductorByteBuffers conductorByteBuffers;
        private InvalidDestinationHandler invalidDestinationHandler;
        private PublisherControlFactory publisherControlFactory = DefaultPublisherControlStrategy::new;

        public Context errorHandler(ErrorHandler errorHandler)
        {
            this.errorHandler = errorHandler;
            return this;
        }

        public Context conductorByteBuffers(ConductorByteBuffers conductorByteBuffers)
        {
            this.conductorByteBuffers = conductorByteBuffers;
            return this;
        }

        public Context invalidDestinationHandler(final InvalidDestinationHandler invalidDestination)
        {
            this.invalidDestinationHandler = invalidDestination;
            return this;
        }

        public Context publisherControlFactory(final PublisherControlFactory publisherControlFactory)
        {
            this.publisherControlFactory = publisherControlFactory;
            return this;
        }
    }
}
