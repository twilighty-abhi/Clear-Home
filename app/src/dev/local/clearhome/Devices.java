package dev.local.clearhome;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns EXIF camera make/model into a friendly name: "samsung" + "SM-S928B" -> "Galaxy S24 Ultra". */
final class Devices {
    private Devices() {}

    // Samsung model-code prefixes (the region suffix after them varies: SM-S928B, SM-S928U1, ...).
    private static final Map<String, String> SAMSUNG = new LinkedHashMap<>();
    static {
        String[][] t = {
                {"S938", "Galaxy S25 Ultra"}, {"S936", "Galaxy S25+"}, {"S931", "Galaxy S25"}, {"S937", "Galaxy S25 Edge"},
                {"S928", "Galaxy S24 Ultra"}, {"S926", "Galaxy S24+"}, {"S921", "Galaxy S24"}, {"S721", "Galaxy S24 FE"},
                {"S918", "Galaxy S23 Ultra"}, {"S916", "Galaxy S23+"}, {"S911", "Galaxy S23"}, {"S711", "Galaxy S23 FE"},
                {"S908", "Galaxy S22 Ultra"}, {"S906", "Galaxy S22+"}, {"S901", "Galaxy S22"},
                {"G998", "Galaxy S21 Ultra"}, {"G996", "Galaxy S21+"}, {"G991", "Galaxy S21"}, {"G990", "Galaxy S21 FE"},
                {"G988", "Galaxy S20 Ultra"}, {"G986", "Galaxy S20+"}, {"G981", "Galaxy S20"}, {"G780", "Galaxy S20 FE"},
                {"N986", "Galaxy Note20 Ultra"}, {"N981", "Galaxy Note20"}, {"N975", "Galaxy Note10+"}, {"N970", "Galaxy Note10"},
                {"F966", "Galaxy Z Fold7"}, {"F956", "Galaxy Z Fold6"}, {"F946", "Galaxy Z Fold5"}, {"F936", "Galaxy Z Fold4"},
                {"F766", "Galaxy Z Flip7"}, {"F741", "Galaxy Z Flip6"}, {"F731", "Galaxy Z Flip5"}, {"F721", "Galaxy Z Flip4"},
                {"A566", "Galaxy A56"}, {"A556", "Galaxy A55"}, {"A546", "Galaxy A54"}, {"A536", "Galaxy A53"}, {"A528", "Galaxy A52s"},
                {"A366", "Galaxy A36"}, {"A356", "Galaxy A35"}, {"A346", "Galaxy A34"}, {"A336", "Galaxy A33"},
                {"A256", "Galaxy A25"}, {"A155", "Galaxy A15"}, {"M556", "Galaxy M55"}, {"M346", "Galaxy M34"},
                {"E546", "Galaxy F54"}, {"M536", "Galaxy M53"},
        };
        for (String[] r : t) SAMSUNG.put(r[0], r[1]);
    }

    private static final String[] ROMAN = {"", "", " II", " III", " IV", " V", " VI", " VII", " VIII"};

    static String friendly(String make, String model) {
        make = make == null ? "" : make.trim();
        model = model == null ? "" : model.trim();
        if (model.isEmpty()) return "";
        String mk = make.toLowerCase(Locale.ROOT);

        if (mk.startsWith("samsung")) {
            Matcher m = Pattern.compile("^(?:SM-)?([A-Z]\\d{3})").matcher(model);
            if (m.find() && SAMSUNG.containsKey(m.group(1))) return SAMSUNG.get(m.group(1));
            if (model.toLowerCase(Locale.ROOT).startsWith("galaxy")) return model;
            return "Samsung " + model;
        }
        if (mk.startsWith("sony")) {
            Matcher a = Pattern.compile("^ILCE-(\\d+)([A-Z]*?)(?:M(\\d))?$").matcher(model);
            if (a.matches()) {
                int gen = a.group(3) == null ? 0 : Integer.parseInt(a.group(3));
                return "Sony α" + a.group(1) + a.group(2) + (gen < ROMAN.length ? ROMAN[gen] : " Mk" + gen);
            }
            Matcher rx = Pattern.compile("^DSC-([A-Z]+\\d+)(?:M(\\d))?$").matcher(model);
            if (rx.matches()) {
                int gen = rx.group(2) == null ? 0 : Integer.parseInt(rx.group(2));
                return "Sony " + rx.group(1) + (gen < ROMAN.length ? ROMAN[gen] : "");
            }
            if (model.startsWith("XQ-")) return "Sony Xperia";
        }
        // Google ("Pixel 8 Pro"), Apple ("iPhone 15 Pro") and most others already use readable names.
        if (model.toLowerCase(Locale.ROOT).startsWith(mk) || mk.isEmpty() || mk.startsWith("apple")
                || mk.startsWith("google")) {
            return model;
        }
        String brand = make.split("[\\s,]+")[0];
        if (brand.equals(brand.toUpperCase(Locale.ROOT))) { // "NIKON" -> "Nikon"; keep "OnePlus" as is
            brand = brand.charAt(0) + brand.substring(1).toLowerCase(Locale.ROOT);
        }
        String rest = model.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT) + " ")
                ? model.substring(brand.length() + 1) : model;
        return brand + " " + rest;
    }
}
