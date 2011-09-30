/* Copyright 2011 SpringSource.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.cloudsupport

import grails.util.GrailsUtil

import org.apache.log4j.Logger
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.core.Ordered

/**
 * Updates beans with connection information from the cloud environment.
 *
 * @author Burt Beckwith
 */
abstract class AbstractCloudBeanPostprocessor implements BeanDefinitionRegistryPostProcessor, Ordered {

	protected Logger log = Logger.getLogger(getClass())

	int getOrder() { 100 }

	/**
	 * {@inheritDoc}
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor#postProcessBeanDefinitionRegistry(
	 * 	org.springframework.beans.factory.support.BeanDefinitionRegistry)
	 */
	void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		log.info 'postProcessBeanDefinitionRegistry'
	}

	/**
	 * {@inheritDoc}
	 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor#postProcessBeanFactory(
	 * 	org.springframework.beans.factory.config.ConfigurableListableBeanFactory)
	 */
	void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {

		log.info 'postProcessBeanFactory start'

		def appConfig = beanFactory.parentBeanFactory.getBean('grailsApplication').config

		if (!isAvailable(beanFactory, appConfig)) {
			log.info 'Not in cloud environment, not processing'
			return
		}

		try {
			def dataSource
			if (beanFactory.containsBean('dataSourceUnproxied')) {
				dataSource = beanFactory.getBean('dataSourceUnproxied')
			}
			else if (beanFactory.containsBean('dataSource')) {
				dataSource = beanFactory.getBean('dataSource')
			}
			if (dataSource) {
				fixDataSource beanFactory, beanFactory.getBean('dataSourceUnproxied'), appConfig
				if (beanFactory.parentBeanFactory.getBean('pluginManager').hasGrailsPlugin('memcached')) {
					fixMemcached beanFactory, appConfig
				}
			}
		}
		catch (Throwable e) {
			handleError e, 'Problem updating DataSource'
		}

		try {
			if (beanFactory.containsBean('rabbitMQConnectionFactory')) {
				fixRabbit beanFactory, appConfig
			}
		}
		catch (Throwable e) {
			handleError e, 'Problem updating Rabbit'
		}

		try {
			if (beanFactory.containsBean('mongo')) {
				fixMongo beanFactory, appConfig
			}
		}
		catch (Throwable e) {
			handleError e, 'Problem updating MongoDB'
		}

		try {
			if (beanFactory.containsBean('compass')) {
				fixCompass beanFactory, appConfig
			}
		}
		catch (Throwable e) {
			handleError e, 'Problem updating Searchable'
		}

		try {
			if (beanFactory.containsBean('redisDatastore')) {
				fixRedis beanFactory, appConfig
			}
		}
		catch (Throwable e) {
			handleError e, 'Problem updating Redis'
		}
	}

	/**
	 * Whether the cloud environment is available.
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 * @return true if available
	 */
	protected abstract boolean isAvailable(ConfigurableListableBeanFactory beanFactory, ConfigObject appConfig)

	/**
	 * Update the DataSource with connect info.
	 * @param beanFactory the Spring bean factory
	 * @param dataSourceBean the DataSource bean
	 * @param appConfig the application config
	 */
	protected void fixDataSource(ConfigurableListableBeanFactory beanFactory,
			dataSourceBean, ConfigObject appConfig) {

		def updatedValues = findDataSourceValues(beanFactory, appConfig)
		if (!updatedValues) {
			return
		}

		// look for pattern like jdbc:mysql://localhost:3306/db?&useUnicode=true&characterEncoding=utf8

		String suffix = ''
		String configUrl = appConfig.dataSource.url
		if (isSupportedJdbcUrl(configUrl) && configUrl.contains('?')) {
			suffix = configUrl.substring(configUrl.indexOf('?'))
		}

		dataSourceBean.driverClassName = updatedValues.driverClassName
		dataSourceBean.url = updatedValues.url + suffix
		dataSourceBean.username = updatedValues.userName
		dataSourceBean.password = updatedValues.password
		log.debug "Updated DataSource from $updatedValues"

		configureDataSourceTimeout dataSourceBean, appConfig
	}

	/**
	 * Whether the JDBC url is supported in the cloud environment.
	 * @param url the URL
	 * @return true if supported
	 */
	protected boolean isSupportedJdbcUrl(String url) {
		url.startsWith('jdbc:mysql:') || url.startsWith('jdbc:postgresql:')
	}

	/**
	 * Return updated DataSource connect info. Return an empty or null map to indicate that
	 * no processing should be done. Values should include:
	 * 	driverClassName
	 * 	url
	 * 	userName
	 * 	password
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 * @return the data
	 */
	protected abstract Map findDataSourceValues(ConfigurableListableBeanFactory beanFactory,
			ConfigObject appConfig)

	/**
	 * Update the DataSource with params to do connection timeout checks.
	 * @param dataSourceBean the DataSource bean
	 * @param appConfig the application config
	 */
	protected void configureDataSourceTimeout(dataSourceBean, appConfig) {
		if (!dataSourceBean.getClass().name.equals('org.apache.commons.dbcp.BasicDataSource')) {
			log.debug "Not configuring DataSource connection checking - datasource isn't a BasicDataSource"
			return
		}

		if (!shouldConfigureDatasourceTimeout(appConfig)) {
			log.debug "Not configuring DataSource connection checking, disabled"
			return
		}

		dataSourceBean.removeAbandoned = true
		dataSourceBean.removeAbandonedTimeout = 300 // 5 minutes
		dataSourceBean.testOnBorrow = true
		dataSourceBean.validationQuery = '/* ping */ SELECT 1'
		log.debug "Configured DataSource connection checking"
	}

	/**
	 * Whether the timeout fixes should be applied.
	 * @param appConfig the application config
	 * @return true to make the timeout changes
	 */
	protected boolean shouldConfigureDatasourceTimeout(ConfigObject appConfig) { true }

	/**
	 * Update Redis with connect info.
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 */
	protected void fixRedis(ConfigurableListableBeanFactory beanFactory, ConfigObject appConfig) {

		def updatedValues = findRedisValues(beanFactory, appConfig)
		if (!updatedValues) {
			return
		}

		def groovyClassLoader = new GroovyClassLoader(getClass().classLoader)

		def clazz
		try {
			clazz = groovyClassLoader.loadClass('org.grails.plugins.redis.RedisDatastoreFactoryBean')
		}
		catch (ClassNotFoundException e) {
			clazz = groovyClassLoader.loadClass('org.grails.datastore.gorm.redis.bean.factory.RedisDatastoreFactoryBean')
		}

		def bean = clazz.newInstance()
		bean.mappingContext = beanFactory.getBean('redisDatastoreMappingContext')
		bean.pluginManager = beanFactory.getBean('pluginManager')

		def newConfig = [:]
		def config = appConfig.grails.redis
		config.each { key, value -> newConfig[key] = value?.toString() }
		newConfig.host = updatedValues.host
		newConfig.password = updatedValues.password
		newConfig.port = updatedValues.port.toString()
		bean.config = newConfig

		beanFactory.registerSingleton 'redisDatastore', bean
		log.debug "Updated Redis from $updatedValues"
	}

	/**
	 * Return updated Redis connect info. Return an empty or null map to indicate that
	 * no processing should be done. Values should include:
	 * 	host
	 * 	port
	 * 	password
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 * @return the data
	 */
	protected abstract Map findRedisValues(ConfigurableListableBeanFactory beanFactory,
			ConfigObject appConfig)

	/**
	 * Update Rabbit with connect info.
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 */
	protected void fixRabbit(ConfigurableListableBeanFactory beanFactory, ConfigObject appConfig) {

		def updatedValues = findRabbitValues(beanFactory, appConfig)
		if (!updatedValues) {
			return
		}

		def groovyClassLoader = new GroovyClassLoader(getClass().classLoader)

		// TODO this needs to keep in sync with rabbitmq plugin
		def config = appConfig.rabbitmq.connectionfactory
		def className = config.className ?: 'org.springframework.amqp.rabbit.connection.CachingConnectionFactory'
		def clazz = groovyClassLoader.loadClass(className)
		def connectionFactory = clazz.newInstance(updatedValues.host)
		connectionFactory.username = updatedValues.userName
		connectionFactory.password = updatedValues.password
		connectionFactory.virtualHost = updatedValues.virtualHost
		connectionFactory.port = updatedValues.port

		connectionFactory.channelCacheSize = config.channelCacheSize ?: 10

		beanFactory.registerSingleton 'rabbitMQConnectionFactory', connectionFactory
		log.debug "Updated Rabbit from $updatedValues"
	}

	/**
	 * Return updated Rabbit connect info. Return an empty or null map to indicate that
	 * no processing should be done. Values should include:
	 * 	host
	 * 	userName
	 * 	password
	 * 	virtualHost
	 * 	port
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 * @return the data
	 */
	protected abstract Map findRabbitValues(ConfigurableListableBeanFactory beanFactory,
			ConfigObject appConfig)

	/**
	 * Update Mongo with connect info.
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 */
	protected void fixMongo(ConfigurableListableBeanFactory beanFactory, ConfigObject appConfig) {
		// nothing to do - config properties are overridden in the plugin descriptor
	}

	/**
	 * Update the location of the Searchable Lucene index.
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 */
	protected void fixCompass(ConfigurableListableBeanFactory beanFactory, ConfigObject appConfig) {
		def compassBean = beanFactory.getBeanDefinition('compass')
		String indexLocation = getCompassIndexRootLocation(appConfig) + '/searchable-index'
		appConfig.searchable.compassConnection = indexLocation
		compassBean.propertyValues.addPropertyValue 'compassConnection', indexLocation
		log.debug "Updated Compass connection details: $indexLocation"
	}

	/**
	 * Get the root directory where the Lucene index should go.
	 * @param appConfig the application config
	 * @return the root path
	 */
	protected abstract String getCompassIndexRootLocation(ConfigObject appConfig)

	/**
	 * Update Memcached with connect info.
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 */
	protected void fixMemcached(ConfigurableListableBeanFactory beanFactory, ConfigObject appConfig) {

		def updatedValues = findMemcachedValues(beanFactory, appConfig)
		if (!updatedValues) {
			return
		}

		BeanDefinition beanDefinition = beanFactory.getBeanDefinition('hibernateProperties')
		PropertyValue propertyValue = beanDefinition.getPropertyValues().getPropertyValue('properties')
		Map properties = propertyValue.getValue()

		String server = updatedValues.host
		if (!server.contains(':')) {
			server += ':11211'
		}
		properties['hibernate.memcached.servers'] = server
		properties['hibernate.memcached.username'] = updatedValues.userName
		properties['hibernate.memcached.password'] = updatedValues.password
		properties['hibernate.cache.provider_class'] = 'com.googlecode.hibernate.memcached.MemcachedCacheProvider'
		properties['hibernate.memcached.connectionFactory'] = 'BinaryConnectionFactory'

		log.debug "Updated Memcached from $updatedValues"
	}

	/**
	 * Return updated Memcached connect info. Return an empty or null map to indicate that
	 * no processing should be done. Values should include:
	 * 	host
	 * 	userName
	 * 	password
	 * @param beanFactory the Spring bean factory
	 * @param appConfig the application config
	 * @return the data
	 */
	protected abstract Map findMemcachedValues(ConfigurableListableBeanFactory beanFactory,
			ConfigObject appConfig)

	protected void handleError(Throwable t, String prefix) {
		GrailsUtil.deepSanitize t
		log.error "$prefix: $t.message", t
	}
}
