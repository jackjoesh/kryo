/* Copyright (c) 2008-2017, Nathan Sweet
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package com.esotericsoftware.kryo.serializers;

import static com.esotericsoftware.minlog.Log.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.TypeVariable;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.reflectasm.FieldAccess;

// BOZO - Make primitive serialization with ReflectASM configurable?

/** Serializes objects using direct field assignment. FieldSerializer is generic and can serialize most classes without any
 * configuration. It is efficient and writes only the field data, without any extra information. It does not support adding,
 * removing, or changing the type of fields without invalidating previously serialized bytes. This can be acceptable in many
 * situations, such as when sending data over a network, but may not be a good choice for long term data storage because the Java
 * classes cannot evolve. Because FieldSerializer attempts to read and write non-public fields by default, it is important to
 * evaluate each class that will be serialized. If fields are public, bytecode generation will be used instead of reflection.
 * @see Serializer
 * @see Kryo#register(Class, Serializer)
 * @see VersionFieldSerializer
 * @see TaggedFieldSerializer
 * @see CompatibleFieldSerializer
 * @author Nathan Sweet
 * @author Roman Levenstein <romixlev@gmail.com> */
public class FieldSerializer<T> extends Serializer<T> {
	final Kryo kryo;
	final Class type;
	final Class componentType;
	final FieldSerializerConfig config;
	final CachedFields cachedFields;
	final TypeVariable[] typeParameters;

	public FieldSerializer (Kryo kryo, Class type) {
		this(kryo, type, null);
	}

	public FieldSerializer (Kryo kryo, Class type, Class[] generics) {
		this(kryo, type, generics, new FieldSerializerConfig());
	}

	public FieldSerializer (Kryo kryo, Class type, Class[] generics, FieldSerializerConfig config) {
		if (config == null) throw new IllegalArgumentException("config cannot be null.");
		this.kryo = kryo;
		this.type = type;
		this.config = config;

		typeParameters = type.getTypeParameters();
		if (typeParameters == null || typeParameters.length == 0)
			componentType = type.getComponentType();
		else
			componentType = null;

		cachedFields = new CachedFields(this, generics);
		cachedFields.update(false);
	}

	public void updateFields () {
		cachedFields.update(false);
	}

	public void setGenerics (Kryo kryo, Class[] generics) {
		if (!config.optimizedGenerics) return;
		cachedFields.generics = generics;
		if (typeParameters != null && typeParameters.length > 0) {
			// There is no need to rebuild all cached fields from scratch.
			// Generic parameter types do not affect the set of fields, offsets of fields,
			// transient and non-transient properties. They only affect the type of
			// fields and serializers selected for each field.
			cachedFields.update(true);
		}
	}

	/** Get generic type parameters of the class controlled by this serializer.
	 * @return generic type parameters or null, if there are none or {@link FieldSerializerConfig#optimizedGenerics} is false. */
	public Class[] getGenerics () {
		return cachedFields.generics;
	}

	protected void initializeCachedFields () {
	}

	/** This method can be called for different fields having the same type. Even though the raw type is the same, if the type is
	 * generic, it could happen that different concrete classes are used to instantiate it. Therefore, in case of different
	 * instantiation parameters, the fields analysis should be repeated.
	 * 
	 * TODO: Cache serializer instances generated for a given set of generic parameters. Reuse it later instead of recomputing
	 * every time. */
	public void write (Kryo kryo, Output output, T object) {
		if (TRACE) trace("kryo", "FieldSerializer.write fields of class: " + object.getClass().getName());

		if (config.optimizedGenerics) {
			// Rebuild cached fields, may result in rebuilding the genericScope.
			if (typeParameters != null && cachedFields.generics != null) cachedFields.update(false);
			if (cachedFields.genericsScope != null) kryo.getGenericsResolver().pushScope(type, cachedFields.genericsScope);
		}

		CachedField[] fields = cachedFields.fields;
		for (int i = 0, n = fields.length; i < n; i++)
			fields[i].write(output, object);

		if (config.serializeTransient) {
			for (int i = 0, n = cachedFields.transientFields.length; i < n; i++)
				cachedFields.transientFields[i].write(output, object);
		}

		if (config.optimizedGenerics && cachedFields.genericsScope != null) kryo.getGenericsResolver().popScope();
	}

	public T read (Kryo kryo, Input input, Class<? extends T> type) {
		if (config.optimizedGenerics) {
			// Rebuild cached fields, may result in rebuilding the genericScope.
			if (typeParameters != null && cachedFields.generics != null) cachedFields.update(false);
			if (cachedFields.genericsScope != null) kryo.getGenericsResolver().pushScope(type, cachedFields.genericsScope);
		}

		T object = create(kryo, input, type);
		kryo.reference(object);

		CachedField[] fields = cachedFields.fields;
		for (int i = 0, n = fields.length; i < n; i++)
			fields[i].read(input, object);

		if (config.serializeTransient) {
			for (int i = 0, n = cachedFields.transientFields.length; i < n; i++)
				cachedFields.transientFields[i].read(input, object);
		}

		if (config.optimizedGenerics && cachedFields.genericsScope != null && kryo.getGenericsResolver() != null)
			kryo.getGenericsResolver().popScope();

		return object;
	}

	/** Used by {@link #read(Kryo, Input, Class)} to create the new object. This can be overridden to customize object creation, eg
	 * to call a constructor with arguments. The default implementation uses {@link Kryo#newInstance(Class)}. */
	protected T create (Kryo kryo, Input input, Class<? extends T> type) {
		return kryo.newInstance(type);
	}

	/** Allows specific fields to be optimized. */
	public CachedField getField (String fieldName) {
		for (CachedField cachedField : cachedFields.fields)
			if (cachedField.name.equals(fieldName)) return cachedField;
		throw new IllegalArgumentException("Field \"" + fieldName + "\" not found on class: " + type.getName());
	}

	public void removeField (String fieldName) {
		cachedFields.removeField(fieldName);
	}

	public void removeField (CachedField field) {
		cachedFields.removeField(field);
	}

	/** Get all fields controlled by this FieldSerializer
	 * @return all fields controlled by this FieldSerializer */
	public CachedField[] getFields () {
		return cachedFields.fields;
	}

	/** Get all transient fields controlled by this FieldSerializer
	 * @return all transient fields controlled by this FieldSerializer */
	public CachedField[] getTransientFields () {
		return cachedFields.transientFields;
	}

	public Class getType () {
		return type;
	}

	public Kryo getKryo () {
		return kryo;
	}

	/** Used by {@link #copy(Kryo, Object)} to create the new object. This can be overridden to customize object creation, eg to
	 * call a constructor with arguments. The default implementation uses {@link Kryo#newInstance(Class)}. */
	protected T createCopy (Kryo kryo, T original) {
		return (T)kryo.newInstance(original.getClass());
	}

	public T copy (Kryo kryo, T original) {
		T copy = createCopy(kryo, original);
		kryo.reference(copy);

		if (config.copyTransient) {
			for (int i = 0, n = cachedFields.transientFields.length; i < n; i++)
				cachedFields.transientFields[i].copy(original, copy);
		}

		for (int i = 0, n = cachedFields.fields.length; i < n; i++)
			cachedFields.fields[i].copy(original, copy);

		return copy;
	}

	public FieldSerializerConfig getFieldSerializerConfig () {
		return config;
	}

	/** Controls how a field will be serialized. */
	static public abstract class CachedField {
		Field field;
		FieldAccess access;
		String name;
		Class valueClass;
		Serializer serializer;
		boolean canBeNull;
		int accessIndex = -1;
		boolean varInt = true, optimizePositive;

		/** @param valueClass The concrete class of the values for this field. This saves 1-2 bytes. The serializer registered for
		 *           the specified class will be used. Only set to a non-null value if the field type in the class definition is
		 *           final or the values for this field will not vary. */
		public void setClass (Class valueClass) {
			this.valueClass = valueClass;
			this.serializer = null;
		}

		/** @param valueClass The concrete class of the values for this field. This saves 1-2 bytes. Only set to a non-null value if
		 *           the field type in the class definition is final or the values for this field will not vary. */
		public void setClass (Class valueClass, Serializer serializer) {
			this.valueClass = valueClass;
			this.serializer = serializer;
		}

		public void setSerializer (Serializer serializer) {
			this.serializer = serializer;
		}

		public Serializer getSerializer () {
			return this.serializer;
		}

		public void setCanBeNull (boolean canBeNull) {
			this.canBeNull = canBeNull;
		}

		public boolean getCanBeNull () {
			return canBeNull;
		}

		/** When true, variable length values are used for int and long fields. Default is true. */
		public void setVarInt (boolean varInt) {
			this.varInt = varInt;
		}

		public boolean getVarInt () {
			return varInt;
		}

		/** When true, variable length int and long values are written with fewer bytes when the values are positive. Default is
		 * false. */
		public void setOptimizePositive (boolean optimizePositive) {
			this.optimizePositive = optimizePositive;
		}

		public boolean getOptimizePositive () {
			return optimizePositive;
		}

		public String getName () {
			return name;
		}

		public Field getField () {
			return field;
		}

		public String toString () {
			return name;
		}

		abstract public void write (Output output, Object object);

		abstract public void read (Input input, Object object);

		abstract public void copy (Object original, Object copy);
	}

	/** Indicates a field should be ignored when its declaring class is registered unless the {@link Kryo#getContext() context} has
	 * a value set for the specified key. This can be useful when a field must be serialized for one purpose, but not for another.
	 * Eg, a class for a networked application could have a field that should not be serialized and sent to clients, but should be
	 * serialized when stored on the server.
	 * @author Nathan Sweet */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	static public @interface Optional {
		public String value();
	}

	/** Used to annotate fields with a specific Kryo serializer. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Bind {
		/** The serializer class to use for this field. */
		Class<? extends Serializer> value();
	}
}
