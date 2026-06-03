package com.openbeken.model;

public  class PixelblazeDevice {
        public String id;
        public String ip;
        public String name;
        public String activePattern;
        
        public PixelblazeDevice(String ip, String name, String activePattern) {
            this.id = "pb_" + ip.replace(".", "_");
            this.ip = ip;
            this.name = name;
            this.activePattern = activePattern;
        }
        
        @Override
        public String toString() {
            return String.format("%s (%s) - %s", name, ip, activePattern);
        }
    }