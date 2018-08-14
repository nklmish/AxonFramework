/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.config;

import org.axonframework.commandhandling.*;
import org.axonframework.commandhandling.callbacks.FutureCallback;
import org.axonframework.commandhandling.model.AggregateIdentifier;
import org.axonframework.commandhandling.model.GenericJpaRepository;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.common.transaction.Transaction;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.EventListener;
import org.axonframework.eventhandling.TrackingEventProcessor;
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration;
import org.axonframework.eventhandling.async.FullConcurrencyPolicy;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventsourcing.EventSourcingHandler;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine;
import org.axonframework.messaging.GenericMessage;
import org.axonframework.messaging.interceptors.TransactionManagingInterceptor;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.*;

import javax.persistence.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.axonframework.commandhandling.model.AggregateLifecycle.apply;
import static org.axonframework.common.AssertUtils.assertWithin;
import static org.axonframework.config.AggregateConfigurer.defaultConfiguration;
import static org.axonframework.config.AggregateConfigurer.jpaMappedConfiguration;
import static org.axonframework.config.ConfigAssertions.assertExpectedModules;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DefaultConfigurerTest {

    private EntityManager em;

    @Before
    public void setUp() {
        Map<String, String> properties = new HashMap<>();
        properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:axontest");
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("eventStore", properties);
        em = emf.createEntityManager();
    }

    @After
    public void tearDown() {
        em.close();
    }

    @Test
    public void defaultConfigurationWithEventSourcing() throws Exception {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .configureCommandBus(c -> new AsynchronousCommandBus())
                                                .configureAggregate(StubAggregate.class)
                                                .buildConfiguration();
        config.start();

        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get());
        assertNotNull(config.repository(StubAggregate.class));
        assertEquals(2, config.getModules().size());
        assertExpectedModules(config,
                              AggregateConfiguration.class,
                              EventProcessingConfiguration.class);
    }

    @Test
    public void defaultConfigurationWithTrackingProcessorConfigurationInMainConfig() {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .registerComponent(TrackingEventProcessorConfiguration.class,
                                                                   c -> TrackingEventProcessorConfiguration
                                                                           .forParallelProcessing(2))
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .registerModule(new EventProcessingConfiguration()
                                                        .usingTrackingProcessors())
                                                .registerModule(
                                                        new EventHandlingConfiguration()
                                                                .registerEventHandler(c -> (EventListener) event -> {
                                                                })
                                                )
                                                .start();
        try {
            TrackingEventProcessor processor = config.eventProcessingConfiguration().eventProcessor(getClass().getPackage().getName(), TrackingEventProcessor.class)
                                                     .orElseThrow(RuntimeException::new);
            assertWithin(5, TimeUnit.SECONDS, () -> assertEquals(2, config.getComponent(TokenStore.class).fetchSegments(processor.getName()).length));
        } finally {
            config.shutdown();
        }
    }

    @Test
    public void defaultConfigurationWithTrackingProcessorExplicitlyConfigured() {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .registerModule(
                                                        new EventHandlingConfiguration()
                                                                .usingTrackingProcessors(
                                                                        c -> TrackingEventProcessorConfiguration.forParallelProcessing(2),
                                                                        c -> new FullConcurrencyPolicy())
                                                                .registerEventHandler(c -> (EventListener) event -> {
                                                                })
                                                )
                                                .start();
        try {
            TrackingEventProcessor processor = config.eventProcessingConfiguration().eventProcessor(getClass().getPackage().getName(), TrackingEventProcessor.class)
                                                     .orElseThrow(RuntimeException::new);
            assertWithin(5, TimeUnit.SECONDS, () -> assertEquals(2, config.getComponent(TokenStore.class).fetchSegments(processor.getName()).length));
        } finally {
            config.shutdown();
        }
    }

    @Test
    public void defaultConfigurationWithUpcaster() {
        AtomicInteger counter = new AtomicInteger();
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureEmbeddedEventStore(c -> new JpaEventStorageEngine(c.serializer(), c.upcasterChain(), c.getComponent(PersistenceExceptionResolver.class), () -> em, c.getComponent(TransactionManager.class)))
                                                .configureAggregate(defaultConfiguration(StubAggregate.class)
                                                                            .configureCommandTargetResolver(c -> command -> new VersionedAggregateIdentifier(command.getPayload().toString(), null)))
                                                .registerEventUpcaster(c -> events -> {
                                                    counter.incrementAndGet();
                                                    return events;
                                                })
                                                .configureTransactionManager(c -> new EntityManagerTransactionManager(em))
                                                .buildConfiguration();
        config.start();

        config.commandGateway().sendAndWait(GenericCommandMessage.asCommandMessage("test"));
        config.commandGateway().sendAndWait(new GenericCommandMessage<>(new GenericMessage<>("test"), "update"));
        assertEquals(1, counter.get());
        assertNotNull(config.repository(StubAggregate.class));
    }

    @Test
    public void testJpaConfigurationWithInitialTransactionManagerJpaRepository() throws Exception {
        EntityManagerTransactionManager transactionManager = spy(new EntityManagerTransactionManager(em));
        Configuration config = DefaultConfigurer.jpaConfiguration(() -> em, transactionManager)
                                                .configureCommandBus(c -> {
                                                    AsynchronousCommandBus commandBus = new AsynchronousCommandBus();
                                                    commandBus.registerHandlerInterceptor(new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class)));
                                                    return commandBus;
                                                })
                                                .configureAggregate(
                                                        defaultConfiguration(StubAggregate.class)
                                                                .configureRepository(c -> new GenericJpaRepository<>(new SimpleEntityManagerProvider(em),
                                                                                                                     StubAggregate.class, c.eventBus(),
                                                                                                                     c.parameterResolverFactory())))
                                                .buildConfiguration();

        config.start();
        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get());
        assertNotNull(config.repository(StubAggregate.class));
        assertEquals(2, config.getModules().size());
        assertExpectedModules(config,
                              EventProcessingConfiguration.class,
                              AggregateConfiguration.class);
        verify(transactionManager).startTransaction();
    }

    @Test
    public void testJpaConfigurationWithInitialTransactionManagerJpaRepositoryFromConfiguration() throws Exception {
        EntityManagerTransactionManager transactionManager = spy(new EntityManagerTransactionManager(em));
        Configuration config = DefaultConfigurer.jpaConfiguration(() -> em, transactionManager)
                                                .configureCommandBus(c -> {
                                                    AsynchronousCommandBus commandBus = new AsynchronousCommandBus();
                                                    commandBus.registerHandlerInterceptor(new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class)));
                                                    return commandBus;
                                                })
                                                .configureAggregate(jpaMappedConfiguration(StubAggregate.class))
                                                .buildConfiguration();

        config.start();
        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get());
        assertNotNull(config.repository(StubAggregate.class));
        assertTrue(config.getModules()
                         .stream()
                         .anyMatch(m -> m instanceof AggregateConfiguration));
        verify(transactionManager).startTransaction();
    }

    @Test
    public void testMissingEntityManagerProviderIsReported() {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureCommandBus(c -> {
                                                    AsynchronousCommandBus commandBus = new AsynchronousCommandBus();
                                                    commandBus.registerHandlerInterceptor(new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class)));
                                                    return commandBus;
                                                })
                                                .configureAggregate(jpaMappedConfiguration(StubAggregate.class))
                                                .buildConfiguration();

        try {
            config.start();
            fail("Expected AxonConfigurationException");
        } catch (AxonConfigurationException e) {
            // expected
        }
    }

    @Test
    public void testJpaConfigurationWithJpaRepository() throws Exception {
        EntityManagerTransactionManager transactionManager = spy(new EntityManagerTransactionManager(em));
        Configuration config = DefaultConfigurer.jpaConfiguration(() -> em)
                                                .registerComponent(TransactionManager.class, c -> transactionManager)
                                                .configureCommandBus(c -> {
                                                    AsynchronousCommandBus commandBus = new AsynchronousCommandBus();
                                                    commandBus.registerHandlerInterceptor(new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class)));
                                                    return commandBus;
                                                })
                                                .configureAggregate(
                                                        defaultConfiguration(StubAggregate.class)
                                                                .configureRepository(c -> new GenericJpaRepository<>(new SimpleEntityManagerProvider(em),
                                                                                                                     StubAggregate.class, c.eventBus(),
                                                                                                                     c.parameterResolverFactory())))
                                                .buildConfiguration();

        config.start();
        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get());
        assertNotNull(config.repository(StubAggregate.class));
        assertEquals(2, config.getModules().size());
        assertExpectedModules(config,
                              EventProcessingConfiguration.class,
                              AggregateConfiguration.class);
        verify(transactionManager).startTransaction();
    }

    @Test
    public void defaultConfigurationWithMonitors() throws Exception {
        MessageCollectingMonitor defaultMonitor = new MessageCollectingMonitor();
        MessageCollectingMonitor commandBusMonitor = new MessageCollectingMonitor();

        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .configureAggregate(StubAggregate.class)
                                                .configureMessageMonitor(c -> (t, n) -> defaultMonitor)
                                                .configureMessageMonitor(CommandBus.class, "commandBus", c -> commandBusMonitor)
                                                .buildConfiguration();
        config.start();

        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get());
        assertEquals(1, defaultMonitor.getMessages().size());
        assertEquals(1, commandBusMonitor.getMessages().size());
    }

    @Test
    public void testRegisterSeveralModules() {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .registerModule(new EventHandlingConfiguration())
                                                .registerModule(new EventHandlingConfiguration())
                                                .configureAggregate(StubAggregate.class)
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .start();

        assertThat(config.getModules().size(), CoreMatchers.is(4));
        assertExpectedModules(config,
                              EventHandlingConfiguration.class,
                              EventHandlingConfiguration.class,
                              AggregateConfiguration.class,
                              EventProcessingConfiguration.class);
    }

    @Test
    public void testModuleHandlersOrdering() {
        ModuleConfiguration module1 = mock(ModuleConfiguration.class);
        ModuleConfiguration module2 = mock(ModuleConfiguration.class);
        ModuleConfiguration module3 = mock(ModuleConfiguration.class);
        when(module1.phase()).thenReturn(2);
        when(module2.phase()).thenReturn(3);
        when(module3.phase()).thenReturn(1);

        Configuration configuration = DefaultConfigurer.defaultConfiguration()
                                                       .registerModule(module1)
                                                       .registerModule(module2)
                                                       .registerModule(module3)
                                                       .start();
        assertNotNull(configuration);
        configuration.shutdown();

        InOrder inOrder = inOrder(module1, module2, module3);
        inOrder.verify(module3).initialize(configuration);
        inOrder.verify(module1).initialize(configuration);
        inOrder.verify(module2).initialize(configuration);
        inOrder.verify(module3).start();
        inOrder.verify(module1).start();
        inOrder.verify(module2).start();
        inOrder.verify(module2).shutdown();
        inOrder.verify(module1).shutdown();
        inOrder.verify(module3).shutdown();
    }

    @Entity(name = "StubAggregate")
    private static class StubAggregate {

        @Id
        @AggregateIdentifier
        private String id;

        public StubAggregate() {
        }

        @CommandHandler
        public StubAggregate(String command, CommandBus commandBus) {
            apply(command);
        }

        @CommandHandler(commandName = "update")
        public void update(String command) {
            apply(1L);
        }

        @EventSourcingHandler
        protected void on(String event) {
            this.id = event;
        }
    }

    private static class EntityManagerTransactionManager implements TransactionManager {
        private final EntityManager em;

        public EntityManagerTransactionManager(EntityManager em) {
            this.em = em;
        }

        @Override
        public Transaction startTransaction() {
            EntityTransaction tx = em.getTransaction();
            if (tx.isActive()) {
                return new Transaction() {
                    @Override
                    public void commit() {
                    }

                    @Override
                    public void rollback() {
                    }
                };
            }
            tx.begin();
            return new Transaction() {
                @Override
                public void commit() {
                    tx.commit();
                }

                @Override
                public void rollback() {
                    tx.rollback();
                }
            };
        }
    }
}
