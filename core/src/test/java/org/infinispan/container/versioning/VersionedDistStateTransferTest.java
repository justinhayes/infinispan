/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.container.versioning;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.VersioningScheme;
import org.infinispan.distribution.MagicKey;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.transaction.LockingMode;
import org.infinispan.util.concurrent.IsolationLevel;
import org.testng.annotations.Test;

import javax.transaction.RollbackException;
import javax.transaction.Transaction;

@Test(testName = "container.versioning.VersionedDistStateTransferTest", groups = "functional")
@CleanupAfterMethod
public class VersionedDistStateTransferTest extends MultipleCacheManagersTest {
   ConfigurationBuilder builder;

   @Override
   protected void createCacheManagers() throws Throwable {
      builder = TestCacheManagerFactory.getDefaultCacheConfiguration(true);

      builder
            .clustering()
               .cacheMode(CacheMode.DIST_SYNC)
               .l1()
                  .disable()
            .versioning()
               .enable()
               .scheme(VersioningScheme.SIMPLE)
            .locking()
               .isolationLevel(IsolationLevel.REPEATABLE_READ)
               .writeSkewCheck(true)
            .transaction()
               .lockingMode(LockingMode.OPTIMISTIC)
               .syncCommitPhase(true);

      createCluster(builder, 4);
      waitForClusterToForm();
   }

   public void testStateTransfer() throws Exception {
      Cache<Object, Object> cache0 = cache(0);
      Cache<Object, Object> cache1 = cache(1);
      Cache<Object, Object> cache2 = cache(2);
      Cache<Object, Object> cache3 = cache(3);

      MagicKey hello = new MagicKey(cache2, "hello");
      cache0.put(hello, "world");

      for (Cache<Object, Object> c: caches()) assert "world".equals(c.get(hello));

      tm(1).begin();
      assert "world".equals(cache1.get(hello));
      Transaction t = tm(1).suspend();

      addClusterEnabledCacheManager(builder);
      Cache<Object, Object> cache4 = cache(4);

      assert "world".equals(cache2.get(hello));
      cacheManagers.get(2).stop();

      // Cause a write skew
      cache4.put(hello, "new world");

      tm(1).resume(t);
      cache1.put(hello, "world2");

      try {
         tm(1).commit();
         assert false: "Should fail";
      } catch (RollbackException expected) {
         // Expected
      }

      assert "new world".equals(cache0.get(hello));
      assert "new world".equals(cache1.get(hello));
      // skip cache2, it has been killed.
      assert "new world".equals(cache3.get(hello));
      assert "new world".equals(cache3.get(hello));
   }
}
