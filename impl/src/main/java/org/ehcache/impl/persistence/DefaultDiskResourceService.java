/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.impl.persistence;

import org.ehcache.CachePersistenceException;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.ResourceType;
import org.ehcache.core.spi.service.DiskResourceService;
import org.ehcache.core.spi.service.FileBasedPersistenceContext;
import org.ehcache.core.spi.service.LocalPersistenceService;
import org.ehcache.core.spi.service.LocalPersistenceService.SafeSpaceIdentifier;
import org.ehcache.impl.internal.concurrent.ConcurrentHashMap;
import org.ehcache.spi.persistence.StateRepository;
import org.ehcache.spi.service.MaintainableService;
import org.ehcache.spi.service.Service;
import org.ehcache.spi.service.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Default implementation of the {@link DiskResourceService} which can be used explicitly when
 * {@link org.ehcache.PersistentUserManagedCache persistent user managed caches} are desired.
 */
public class DefaultDiskResourceService implements DiskResourceService {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDiskResourceService.class);
  private static final String PERSISTENCE_SPACE_OWNER = "file";

  private final ConcurrentMap<String, PersistenceSpace> knownPersistenceSpaces;
  private volatile LocalPersistenceService persistenceService;

  public DefaultDiskResourceService() {
    this.knownPersistenceSpaces = new ConcurrentHashMap<String, PersistenceSpace>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start(final ServiceProvider<Service> serviceProvider) {
    persistenceService = serviceProvider.getService(LocalPersistenceService.class);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void startForMaintenance(ServiceProvider<MaintainableService> serviceProvider) {
    persistenceService = serviceProvider.getService(LocalPersistenceService.class);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    persistenceService = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean handlesResourceType(ResourceType<?> resourceType) {
    return persistenceService != null && ResourceType.Core.DISK.equals(resourceType);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public PersistenceSpaceIdentifier<DiskResourceService> getPersistenceSpaceIdentifier(String name, CacheConfiguration<?, ?> config) throws CachePersistenceException {
    if (persistenceService == null) {
      return null;
    }
    boolean persistent = config.getResourcePools().getPoolForResource(ResourceType.Core.DISK).isPersistent();
    while (true) {
      PersistenceSpace persistenceSpace = knownPersistenceSpaces.get(name);
      if (persistenceSpace != null) {
        return persistenceSpace.identifier;
      }
      PersistenceSpace newSpace = createSpace(name, persistent);
      if (newSpace != null) {
        return newSpace.identifier;
      }
    }
  }

  @Override
  public void releasePersistenceSpaceIdentifier(PersistenceSpaceIdentifier<?> identifier) throws CachePersistenceException {
    String name = null;
    for (Map.Entry<String, PersistenceSpace> entry : knownPersistenceSpaces.entrySet()) {
      if (entry.getValue().identifier.equals(identifier)) {
        name = entry.getKey();
      }
    }
    if (name == null) {
      throw new CachePersistenceException("Unknown space " + identifier);
    }
    PersistenceSpace persistenceSpace = knownPersistenceSpaces.remove(name);
    if (persistenceSpace != null) {
      for (FileBasedStateRepository stateRepository : persistenceSpace.stateRepositories.values()) {
        try {
          stateRepository.close();
        } catch (IOException e) {
          LOGGER.warn("StateRepository close failed - destroying persistence space {} to prevent corruption", identifier, e);
          persistenceService.destroySafeSpace(((DefaultPersistenceSpaceIdentifier)identifier).persistentSpaceId, true);
        }
      }
    }
  }

  private PersistenceSpace createSpace(String name, boolean persistent) throws CachePersistenceException {
    DefaultPersistenceSpaceIdentifier persistenceSpaceIdentifier =
        new DefaultPersistenceSpaceIdentifier(persistenceService.createSafeSpaceIdentifier(PERSISTENCE_SPACE_OWNER, name));
    PersistenceSpace persistenceSpace = new PersistenceSpace(persistenceSpaceIdentifier);
    if (knownPersistenceSpaces.putIfAbsent(name, persistenceSpace) == null) {
      boolean created = false;
      try {
        if (!persistent) {
          persistenceService.destroySafeSpace(persistenceSpaceIdentifier.persistentSpaceId, true);
        }
        persistenceService.createSafeSpace(persistenceSpaceIdentifier.persistentSpaceId);
        created = true;
      } finally {
        if (!created) {
          // this happens only if an exception is thrown..clean up for any throwable..
          knownPersistenceSpaces.remove(name, persistenceSpace);
        }
      }
      return persistenceSpace;
    } else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void destroy(String name) throws CachePersistenceException {
    if (persistenceService == null) {
      return;
    }
    PersistenceSpace space = knownPersistenceSpaces.remove(name);
    SafeSpaceIdentifier identifier = (space == null) ?
        persistenceService.createSafeSpaceIdentifier(PERSISTENCE_SPACE_OWNER, name) : space.identifier.persistentSpaceId;
    persistenceService.destroySafeSpace(identifier, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void destroyAll() {
    if (persistenceService == null) {
      return;
    }
    persistenceService.destroyAll(PERSISTENCE_SPACE_OWNER);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StateRepository getStateRepositoryWithin(PersistenceSpaceIdentifier<?> identifier, String name)
      throws CachePersistenceException {
    PersistenceSpace persistenceSpace = getPersistenceSpace(identifier);
    if (persistenceSpace != null) {
      FileBasedStateRepository stateRepository = new FileBasedStateRepository(
          FileUtils.createSubDirectory(persistenceSpace.identifier.persistentSpaceId.getRoot(), name));
      FileBasedStateRepository previous = persistenceSpace.stateRepositories.putIfAbsent(name, stateRepository);
      if (previous != null) {
        return previous;
      } else {
        return stateRepository;
      }
    }
    throw new CachePersistenceException("Unknown space " + identifier);
  }

  private PersistenceSpace getPersistenceSpace(PersistenceSpaceIdentifier<?> identifier) {
    for (PersistenceSpace persistenceSpace : knownPersistenceSpaces.values()) {
      if (persistenceSpace.identifier.equals(identifier)) {
        return persistenceSpace;
      }
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FileBasedPersistenceContext createPersistenceContextWithin(PersistenceSpaceIdentifier<?> identifier, String name)
      throws CachePersistenceException {
    if (containsSpace(identifier)) {
      return new DefaultFileBasedPersistenceContext(
          FileUtils.createSubDirectory(((DefaultPersistenceSpaceIdentifier)identifier).persistentSpaceId.getRoot(), name));
    } else {
      throw new CachePersistenceException("Unknown space: " + identifier);
    }
  }

  private boolean containsSpace(PersistenceSpaceIdentifier<?> identifier) {
    for (PersistenceSpace persistenceSpace : knownPersistenceSpaces.values()) {
      if (persistenceSpace.identifier.equals(identifier)) {
        return true;
      }
    }
    return false;
  }

  private static class PersistenceSpace {
    final DefaultPersistenceSpaceIdentifier identifier;
    final ConcurrentMap<String, FileBasedStateRepository> stateRepositories = new ConcurrentHashMap<String, FileBasedStateRepository>();

    private PersistenceSpace(DefaultPersistenceSpaceIdentifier identifier) {
      this.identifier = identifier;
    }
  }

  private static class DefaultPersistenceSpaceIdentifier implements PersistenceSpaceIdentifier<DiskResourceService> {
    final SafeSpaceIdentifier persistentSpaceId;

    private DefaultPersistenceSpaceIdentifier(SafeSpaceIdentifier persistentSpaceId) {
      this.persistentSpaceId = persistentSpaceId;
    }

    @Override
    public Class<DiskResourceService> getServiceType() {
      return DiskResourceService.class;
    }

    @Override
    public String toString() {
      return persistentSpaceId.toString();
    }

    // no need to override equals and hashcode as references are private and created in a protected fashion
    // within this class. So two space identifiers are equal iff their references are equal.
  }

  private static class DefaultFileBasedPersistenceContext implements FileBasedPersistenceContext {
    private final File directory;

    private DefaultFileBasedPersistenceContext(File directory) {
      this.directory = directory;
    }

    @Override
    public File getDirectory() {
      return directory;
    }
  }
}