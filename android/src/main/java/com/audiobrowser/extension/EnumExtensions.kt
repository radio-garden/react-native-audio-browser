package com.audiobrowser.extension

/** The entry of enum [T] whose selector result equals [value] (`Foo::code.find(x)`), or null. */
inline fun <reified T : Enum<T>, V> ((T) -> V).find(value: V): T? {
  return enumValues<T>().firstOrNull { this(it) == value }
}
