/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Fouad Almalki
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.fouad.jtb.core.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.TimeZone;

/**
 * Utility class to handle conversions between JSON and Java object and vice versa.
 */
public class JsonUtils
{
	public static final ObjectMapper mapper = new ObjectMapper();

	static {
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
		mapper.configure(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, false);
		mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
		mapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

		mapper.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	public static <T> T toJavaObject(String json, Class<T> clazz) throws IOException
	{
		return mapper.readValue(json, clazz);
	}
	
	public static <T, R> T toJavaObject(String json, TypeReference typeReference) throws IOException
	{
		return mapper.readValue(json, typeReference);
	}
	
	public static String toJson(Object javaObject) throws IOException
	{
		return mapper.writeValueAsString(javaObject);
	}
}