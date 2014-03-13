/* Copyright 2014  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

package nest.sparkle.loader.kafka

import com.typesafe.config.ConfigFactory
import nest.sparkle.util.ConfigureLog4j

/** (for tests) load the config file and initialize. 
 *  expose a loaderConfig value so that tests can use the config.  */
trait KafkaTestConfig { 
  private lazy val baseConfig = ConfigFactory.load()
  lazy val loaderConfig = {
    val config = baseConfig.getConfig("kafka-loader")
    ConfigureLog4j.configure(config)
    config
  }
}