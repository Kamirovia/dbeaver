/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
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
package org.jkiss.dbeaver.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.data.DBDBinaryFormatter;
import org.jkiss.dbeaver.model.data.DBDDataFormatter;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.sql.SQLDataSource;
import org.jkiss.dbeaver.model.struct.DBSDataType;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.dbeaver.model.struct.DBSTypedObjectEx;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.Locale;

/**
 * DB value formatting utilities
 */
public final class DBValueFormatting {

    public static final DecimalFormat NATIVE_DECIMAL_FORMATTER = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

    private static final Log log = Log.getLog(DBValueFormatting.class);

    static {
        DBValueFormatting.NATIVE_DECIMAL_FORMATTER.setMaximumFractionDigits(340);
    }

    @NotNull
    public static DBPImage getTypeImage(@NotNull DBSTypedObject typedObject)
    {
        if (typedObject instanceof DBSTypedObjectEx) {
            DBSDataType dataType = ((DBSTypedObjectEx) typedObject).getDataType();
            if (dataType instanceof DBPImageProvider) {
                DBPImage image = ((DBPImageProvider) dataType).getObjectImage();
                if (image != null) {
                    return image;
                }
            }
        }
        return getDefaultTypeImage(typedObject);
    }

    @NotNull
    public static DBPImage getDefaultTypeImage(DBSTypedObject typedObject) {
        String typeName = typedObject.getTypeName();
        switch (typedObject.getDataKind()) {
            case BOOLEAN:
                return DBIcon.TYPE_BOOLEAN;
            case STRING:
                return DBIcon.TYPE_STRING;
            case NUMERIC:
                return DBIcon.TYPE_NUMBER;
            case DATETIME:
                return DBIcon.TYPE_DATETIME;
            case BINARY:
                return DBIcon.TYPE_BINARY;
            case CONTENT:
                if (typeName.contains("XML") || typeName.contains("xml")) {
                    return DBIcon.TYPE_XML;
                } else if (typeName.contains("CHAR") || typeName.contains("char")) {
                    return DBIcon.TYPE_TEXT;
                }
                return DBIcon.TYPE_LOB;
            case ARRAY:
                return DBIcon.TYPE_ARRAY;
            case STRUCT:
                return DBIcon.TYPE_STRUCT;
            case DOCUMENT:
                return DBIcon.TYPE_DOCUMENT;
            case REFERENCE:
                return DBIcon.TYPE_REFERENCE;
            case ROWID:
                return DBIcon.TYPE_ROWID;
            case OBJECT:
                if (typeName != null && (typeName.contains(DBConstants.TYPE_NAME_UUID) || typeName.contains(DBConstants.TYPE_NAME_UUID2))) {
                    return DBIcon.TYPE_UUID;
                }
                return DBIcon.TYPE_OBJECT;
            case ANY:
                return DBIcon.TYPE_ANY;
            default:
                return DBIcon.TYPE_UNKNOWN;
        }
    }

    @NotNull
    public static DBPImage getObjectImage(DBPObject object)
    {
        return getObjectImage(object, true);
    }

    @Nullable
    public static DBPImage getObjectImage(DBPObject object, boolean useDefault)
    {
        DBPImage image = null;
        if (object instanceof DBPImageProvider) {
            image = ((DBPImageProvider)object).getObjectImage();
        }
        if (image == null) {
            if (object instanceof DBSTypedObject) {
                image = getTypeImage((DBSTypedObject) object);
            }
            if (image == null && useDefault) {
                image = DBIcon.TYPE_OBJECT;
            }
        }
        return image;
    }

    @NotNull
    public static DBDBinaryFormatter getBinaryPresentation(@NotNull DBPDataSource dataSource)
    {
        String id = dataSource.getContainer().getPreferenceStore().getString(ModelPreferences.RESULT_SET_BINARY_PRESENTATION);
        if (id != null) {
            DBDBinaryFormatter formatter = getBinaryPresentation(id);
            if (formatter != null) {
                return formatter;
            }
        }
        return DBConstants.BINARY_FORMATS[0];
    }

    @Nullable
    public static DBDBinaryFormatter getBinaryPresentation(String id)
    {
        for (DBDBinaryFormatter formatter : DBConstants.BINARY_FORMATS) {
            if (formatter.getId().equals(id)) {
                return formatter;
            }
        }
        return null;
    }

    public static String getDefaultBinaryFileEncoding(@NotNull DBPDataSource dataSource)
    {
        DBPPreferenceStore preferenceStore = dataSource.getContainer().getPreferenceStore();
        String fileEncoding = preferenceStore.getString(ModelPreferences.CONTENT_HEX_ENCODING);
        if (CommonUtils.isEmpty(fileEncoding)) {
            fileEncoding = GeneralUtils.getDefaultFileEncoding();
        }
        return fileEncoding;
    }

    @Nullable
    public static Number convertStringToNumber(String text, Class<?> hintType, @NotNull DBDDataFormatter formatter)
    {
        if (text == null || text.length() == 0) {
            return null;
        }
        try {
            if (hintType == Long.class) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException e) {
                    return new BigInteger(text);
                }
            } else if (hintType == Integer.class) {
                return Integer.valueOf(text);
            } else if (hintType == Short.class) {
                return Short.valueOf(text);
            } else if (hintType == Byte.class) {
                return Byte.valueOf(text);
            } else if (hintType == Float.class) {
                return Float.valueOf(text);
            } else if (hintType == Double.class) {
                return Double.valueOf(text);
            } else if (hintType == BigInteger.class) {
                return new BigInteger(text);
            } else {
                return new BigDecimal(text);
            }
        } catch (NumberFormatException e) {
            try {
                return (Number)formatter.parseValue(text, hintType);
            } catch (ParseException e1) {
                log.debug("Can't parse numeric value [" + text + "] using formatter: " + e.getMessage());
                return null;
            }
        }
    }

    public static String convertNumberToNativeString(Number value) {
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        } else if (value instanceof Float || value instanceof Double) {
            return NATIVE_DECIMAL_FORMATTER.format(value);
        } else {
            return value.toString();
        }

    }

    public static String getBooleanString(boolean propertyValue) {
        return propertyValue ? DBConstants.BOOLEAN_PROP_YES : DBConstants.BOOLEAN_PROP_NO;
    }

    public static String formatBinaryString(@NotNull DBPDataSource dataSource, @NotNull byte[] data, @NotNull DBDDisplayFormat format) {
        return formatBinaryString(dataSource, data, format, false);
    }

    public static String formatBinaryString(@NotNull DBPDataSource dataSource, @NotNull byte[] data, @NotNull DBDDisplayFormat format, boolean forceLimit) {
        DBDBinaryFormatter formatter;
        if (format == DBDDisplayFormat.NATIVE && dataSource instanceof SQLDataSource) {
            formatter = ((SQLDataSource) dataSource).getSQLDialect().getNativeBinaryFormatter();
        } else {
            formatter = getBinaryPresentation(dataSource);
        }
        // Convert bytes to string
        int length = data.length;
        if (format == DBDDisplayFormat.UI || forceLimit) {
            int maxLength = dataSource.getContainer().getPreferenceStore().getInt(ModelPreferences.RESULT_SET_BINARY_STRING_MAX_LEN);
            if (length > maxLength) {
                length = maxLength;
            }
        }
        String string = formatter.toString(data, 0, length);
        if (format == DBDDisplayFormat.NATIVE || length == data.length) {
            // Do not append ... for native formatter - it may contain expressions
            return string;
        }
        return string + "..." + " [" + data.length + "]";
    }

    @NotNull
    public static String getDefaultValueDisplayString(@Nullable Object value, @NotNull DBDDisplayFormat format)
    {
        if (DBUtils.isNullValue(value)) {
            if (format == DBDDisplayFormat.UI) {
                return DBConstants.NULL_VALUE_LABEL;
            } else {
                return "";
            }
        }
        if (value instanceof CharSequence) {
            return value.toString();
        }
        if (value.getClass().isArray()) {
            if (value.getClass().getComponentType() == Byte.TYPE) {
                byte[] bytes = (byte[]) value;
                int length = bytes.length;
                if (length > 2000) length = 2000;
                String string = CommonUtils.toHexString(bytes, 0, length);
                return bytes.length > 2000 ? string + "..." : string;
            } else {
                return GeneralUtils.makeDisplayString(value).toString();
            }
        }
        String className = value.getClass().getName();
        if (className.startsWith("java.lang") || className.startsWith("java.util")) {
            // Standard types just use toString
            return value.toString();
        }
        // Unknown types print their class name
        boolean hasToString;
        try {
            hasToString = value.getClass().getMethod("toString").getDeclaringClass() != Object.class;
        } catch (Throwable e) {
            log.debug(e);
            hasToString = false;
        }
        if (hasToString) {
            return value.toString();
        } else {
            return "[" + value.getClass().getSimpleName() + "]";
        }
    }
}
