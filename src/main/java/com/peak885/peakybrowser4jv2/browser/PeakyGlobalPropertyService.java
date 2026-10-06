package com.peak885.peakybrowser4jv2.browser;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PeakyGlobalPropertyService
        implements IGlobalPropertyService {

    private final Map<IPropertyKey, Object> properties =
            new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return new PeakyPropertyKey(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) properties.get(key);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            properties.remove(key);
        } else {
            properties.put(key, value);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = properties.get(key);
        return value != null ? (T) value : defaultValue;
    }

    @Override
    public String getPropertyString(
            IPropertyKey key,
            String defaultValue) {

        Object value = properties.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static final class PeakyPropertyKey
            implements IPropertyKey {

        private final String name;

        private PeakyPropertyKey(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }

        @Override
        public int hashCode() {
            return this.name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof PeakyPropertyKey)) {
                return false;
            }

            PeakyPropertyKey other = (PeakyPropertyKey) obj;
            return this.name.equals(other.name);
        }
    }
}