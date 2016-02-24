/*
 * Copyright 2015 The original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.azure.config.view

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.Agent
import com.netflix.spinnaker.cats.provider.ProviderSynchronizerTypeWrapper
import com.netflix.spinnaker.clouddriver.azure.AzureCloudProvider
import com.netflix.spinnaker.clouddriver.azure.resources.loadbalancer.cache.AzureLoadBalancerCachingAgent
import com.netflix.spinnaker.clouddriver.azure.resources.network.cache.AzureNetworkCachingAgent
import com.netflix.spinnaker.clouddriver.azure.resources.securitygroup.cache.AzureSecurityGroupCachingAgent
import com.netflix.spinnaker.clouddriver.azure.resources.subnet.cache.AzureSubnetCachingAgent
import com.netflix.spinnaker.clouddriver.azure.resources.vmimage.cache.AzureVMImageCachingAgent
import com.netflix.spinnaker.clouddriver.azure.security.AzureNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsRepository
import com.netflix.spinnaker.clouddriver.azure.resources.common.cache.provider.AzureInfrastructureProvider
import com.netflix.spinnaker.clouddriver.security.ProviderUtils
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Scope

import java.util.concurrent.ConcurrentHashMap

@Configuration
@ConditionalOnProperty('azure.enabled')
class AzureInfrastructureProviderConfig {
  @Bean
  @DependsOn('azureNamedAccountCredentials')
  AzureInfrastructureProvider azureInfrastructureProvider(AzureCloudProvider azureCloudProvider,
                                                            AccountCredentialsRepository accountCredentialsRepository,
                                                            ObjectMapper objectMapper,
                                                            Registry registry) {
    def azureInfrastructureProvider = new AzureInfrastructureProvider(azureCloudProvider,Collections.newSetFromMap(new ConcurrentHashMap<Agent, Boolean>()))

    synchronizeAzureInfrastructureProvider(azureInfrastructureProvider,
                                           azureCloudProvider,
                                           accountCredentialsRepository,
                                           objectMapper,
                                           registry)

    azureInfrastructureProvider
  }

  @Bean
  AzureInfrastructureProviderSynchronizerTypeWrapper azureInfrastructureProviderSynchronizerTypeWrapper() {
    new AzureInfrastructureProviderSynchronizerTypeWrapper()
  }

  class AzureInfrastructureProviderSynchronizerTypeWrapper implements ProviderSynchronizerTypeWrapper {
    @Override
    Class getSynchronizerType() {
      return AzureInfrastructureProviderSynchronizer
    }
  }

  class AzureInfrastructureProviderSynchronizer {}

  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  @Bean
  AzureInfrastructureProviderSynchronizer synchronizeAzureInfrastructureProvider(AzureInfrastructureProvider azureInfrastructureProvider,
                                                                             AzureCloudProvider azureCloudProvider,
                                                                             AccountCredentialsRepository accountCredentialsRepository,
                                                                             ObjectMapper objectMapper,
                                                                             Registry registry) {
    def scheduledAccounts = ProviderUtils.getScheduledAccounts(azureInfrastructureProvider)
    def allAccounts = ProviderUtils.buildThreadSafeSetOfAccounts(accountCredentialsRepository, AzureNamedAccountCredentials)

    allAccounts.each { AzureNamedAccountCredentials creds ->
      creds.regions.each { region ->
        if (!scheduledAccounts.contains(creds.accountName)) {
          def newlyAddedAgents = []

          newlyAddedAgents << new AzureLoadBalancerCachingAgent(azureCloudProvider, creds.accountName, creds.credentials, region.name, objectMapper)
          newlyAddedAgents << new AzureSecurityGroupCachingAgent(azureCloudProvider, creds.accountName, creds.credentials, region.name, objectMapper)
          newlyAddedAgents << new AzureNetworkCachingAgent(azureCloudProvider, creds.accountName, creds.credentials, region.name, objectMapper)
          newlyAddedAgents << new AzureSubnetCachingAgent(azureCloudProvider, creds.accountName, creds.credentials, region.name, objectMapper)
          newlyAddedAgents << new AzureVMImageCachingAgent(azureCloudProvider, creds.accountName, creds.credentials, region.name, objectMapper)

          // If there is an agent scheduler, then this provider has been through the AgentController in the past.
          // In that case, we need to do the scheduling here (because accounts have been added to a running system).
          if (azureInfrastructureProvider.agentScheduler) {
            ProviderUtils.rescheduleAgents(azureInfrastructureProvider, newlyAddedAgents)
          }

          azureInfrastructureProvider.agents.addAll(newlyAddedAgents)
        }
      }
    }

    new AzureInfrastructureProviderSynchronizer()
  }
}
