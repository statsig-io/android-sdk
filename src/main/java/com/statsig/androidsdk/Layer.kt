package com.statsig.androidsdk

/**
 * A helper class for interfacing with Layers defined in the Statsig console
 */
class Layer internal constructor(
  private val client: StatsigClient,
  private val name: String,
  private val jsonValue: Map<String, Any> = mapOf(),
  private val rule: String = "",
  private val secondaryExposures: Array<Map<String, String>> = arrayOf(),
  private val undelegatedSecondaryExposures: Array<Map<String, String>> = arrayOf(),
  private val isUserInExperiment: Boolean = false,
  private val isExperimentActive: Boolean = false,
  private val isDeviceBased: Boolean = false,
  private val allocatedExperimentName: String = "",
  private val explicitParameters: Set<String>? = null,
) {

  /**
   * Gets a value from the Layer, falling back to the provided default value
   * @param key the index within the Layer to fetch a value from
   * @param default the default value to return if the expected key does not exist in the Layer
   * @return the value at the given key, or the default value if not found
   */
  fun getString(key: String, default: String?): String? {
    return get(key, default, jsonValue)
  }

  /**
   * Gets a value from the Layer, falling back to the provided default value
   * @param key the index within the Layer to fetch a value from
   * @param default the default value to return if the expected key does not exist in the Layer
   * @return the value at the given key, or the default value if not found
   */
  fun getBoolean(key: String, default: Boolean): Boolean {
    return get(key, default, jsonValue)
  }

  /**
   * Gets a value from the Layer, falling back to the provided default value
   * @param key the index within the Layer to fetch a value from
   * @param default the default value to return if the expected key does not exist in the Layer
   * @return the value at the given key, or the default value if not found
   */
  fun getDouble(key: String, default: Double): Double {
    return get<Number>(key, default, jsonValue).toDouble()
  }

  /**
   * Gets a value from the Layer, falling back to the provided default value
   * @param key the index within the Layer to fetch a value from
   * @param default the default value to return if the expected key does not exist in the Layer
   * @return the value at the given key, or the default value if not found
   */
  fun getInt(key: String, default: Int): Int {
    return get<Number>(key, default, jsonValue).toInt()
  }

  /**
   * Gets a value from the Layer, falling back to the provided default value
   * @param key the index within the Layer to fetch a value from
   * @param default the default value to return if the expected key does not exist in the Layer
   * @return the value at the given key, or the default value if not found
   */
  fun getLong(key: String, default: Long): Long {
    return get<Number>(key, default, jsonValue).toLong()
  }

  /**
   * Gets a value from the Layer, falling back to the provided default value
   * @param key the index within the Layer to fetch a value from
   * @param default the default value to return if the expected key does not exist in the Layer
   * @return the value at the given key, or the default value if not found
   */
  fun getArray(key: String, default: Array<*>?): Array<*>? {
    var value = jsonValue[key] as? Array<*>
    if (value == null) {
      value = (jsonValue[key] as? ArrayList<*>)?.toTypedArray()
    }

    if (value != null) {
      logParameterExposure(key)
    }

    return value ?: default
  }

  /**
   * Gets a dictionary from the Layer, falling back to the provided default value
   * @param key the index within the Layer to fetch a dictionary from
   * @param default the default value to return if the expected key does not exist in the Layer
   * @return the value at the given key, or the default value if not found
   */
  fun getDictionary(key: String, default: Map<String, Any>?): Map<String, Any>? {
    return get(key, default, jsonValue)
  }

  /**
   * Gets a value from the Layer as a DynamicConfig, or null if not found
   * @param key the index within the Layer to fetch a value from
   * @return the value at the given key as a DynamicConfig, or null
   */
  fun getConfig(key: String): DynamicConfig? {
    return when (val value = get(key, null as Map<String, Any>?, jsonValue)) {
      is Map<String, Any> -> DynamicConfig(
        key,
        value,
        this.rule
      )
      else -> null
    }
  }

  fun getName(): String {
    return this.name
  }

  fun getIsUserInExperiment(): Boolean {
    return this.isUserInExperiment
  }

  fun getIsExperimentActive(): Boolean {
    return this.isExperimentActive
  }

  internal fun getRuleID(): String {
    return this.rule
  }

  internal fun getSecondaryExposures(): Array<Map<String, String>> {
    return this.secondaryExposures
  }

  internal fun getUndelegatedSecondaryExposures(): Array<Map<String, String>> {
    return this.undelegatedSecondaryExposures
  }

  internal fun getAllocatedExperimentName(): String? {
    return this.allocatedExperimentName
  }

  internal fun getExplicitParameters(): Set<String>? {
    return this.explicitParameters
  }

  private fun logParameterExposure(key: String) {
    this.client.logLayerParameterExposure(this, key)
  }

  /**
   * We should not just expose this function as inline reified is copied to every place it is used.
   */
  private inline fun <reified T> get(key: String, default: T, jsonValue: Map<String, Any>): T {
    val value = jsonValue[key] as? T
    if (value != null) {
      logParameterExposure(key)
    }
    return value ?: default
  }
}
