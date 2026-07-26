/*
 * Copyright (C) 2013 The CyanogenMod Project
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

package org.omnirom.omnijaws;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnirom.omnijaws.WeatherInfo.DayForecast;

import android.content.Context;
import android.content.res.Resources;
import android.location.Location;
import android.text.TextUtils;
import android.util.Log;

public class OpenWeatherMapProvider extends AbstractWeatherProvider {
    private static final String TAG = "OpenWeatherMapProvider";

    private static final int FORECAST_DAYS = 5;
    // IchthysOS: the free/baked keys don't have One Call 3.0 access (401), so use the free
    // 2.5 current-weather + 5-day/3-hour forecast endpoints instead.
    private static final String URL_WEATHER =
            "https://api.openweathermap.org/data/2.5/weather?%s&units=%s&lang=%s&appid=%s";
    private static final String URL_FORECAST =
            "https://api.openweathermap.org/data/2.5/forecast?%s&units=%s&lang=%s&appid=%s";

    private List<String> mKeys = new ArrayList<String>();
    private boolean mHasAPIKey;
    private int mRequestNumber;

    public OpenWeatherMapProvider(Context context) {
        super(context);
        loadKeys();
        mHasAPIKey = getAPIKey() != null;
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return handleWeatherRequest(id, metric);
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES, location.getLatitude(), location.getLongitude());
        return handleWeatherRequest(coordinates, metric);
    }

    private WeatherInfo handleWeatherRequest(String selection, boolean metric) {
        if (!mHasAPIKey) {
            log(TAG, "no API key provided");
            return null;
        }
        mRequestNumber++;
        String units = metric ? "metric" : "imperial";
        String locale = getLanguageCode();
        String key = getAPIKey();
        String conditionUrl = String.format(Locale.US, URL_WEATHER, selection, units, locale, key);
        String conditionResponse = retrieve(conditionUrl);
        if (conditionResponse == null) {
            return null;
        }
        log(TAG, "Condition URL = " + conditionUrl + " returning a response of " + conditionResponse);
        String forecastUrl = String.format(Locale.US, URL_FORECAST, selection, units, locale, key);
        String forecastResponse = retrieve(forecastUrl);

        try {
            JSONObject conditions = new JSONObject(conditionResponse);
            JSONObject main = conditions.getJSONObject("main");
            JSONObject weather = conditions.getJSONArray("weather").getJSONObject(0);
            JSONObject wind = conditions.optJSONObject("wind");
            float windSpeed = wind != null ? (float) wind.optDouble("speed", 0) : 0f;
            if (metric) {
                // 2.5 metric speeds are m/s -> convert to our common metric unit km/h
                windSpeed *= 3.6f;
            }

            JSONObject forecastRoot =
                    forecastResponse != null ? new JSONObject(forecastResponse) : null;
            ArrayList<DayForecast> forecasts = (forecastRoot != null && forecastRoot.has("list"))
                    ? parseForecasts(forecastRoot.getJSONArray("list"), metric)
                    : new ArrayList<DayForecast>();

            String city = getWeatherDataLocality(selection);

            WeatherInfo w = new WeatherInfo(mContext, selection, city,
                    /* condition */ "",
                    /* conditionCode */ mapConditionIconToCode(
                    weather.getString("icon"), weather.getInt("id")),
                    /* temperature */ sanitizeTemperature(main.getDouble("temp"), metric),
                    /* humidity */ (float) main.getDouble("humidity"),
                    /* wind */ windSpeed,
                    /* windDir */ wind != null && wind.has("deg") ? wind.getInt("deg") : 0,
                    metric,
                    forecasts,
                    System.currentTimeMillis());

            if (main.has("feels_like")) {
                w.setFeelsLike(sanitizeTemperature(main.getDouble("feels_like"), metric));
            }
            if (main.has("pressure")) {
                w.setPressure((float) main.getDouble("pressure"));
            }
            if (conditions.has("visibility")) {
                float vis = (float) conditions.getDouble("visibility");
                w.setVisibility(metric ? vis / 1000f : vis / 1609.34f);
            }

            JSONObject sys = conditions.optJSONObject("sys");
            if (sys != null) {
                if (sys.has("sunrise")) {
                    w.setSunrise(sys.getLong("sunrise") * 1000L);
                }
                if (sys.has("sunset")) {
                    w.setSunset(sys.getLong("sunset") * 1000L);
                }
            }

            if (forecastRoot != null && forecastRoot.has("list")) {
                w.setHourlyForecasts(parseHourlyForecasts(forecastRoot.getJSONArray("list"), metric));
            }

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (selection = " + selection
                    + ", lang = " + locale + ")", e);
        }

        return null;
    }

    // 2.5 forecast is a 3-hourly list; aggregate it into daily min/max + a midday condition.
    private ArrayList<DayForecast> parseForecasts(JSONArray list, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<DayForecast>();
        java.util.LinkedHashMap<String, float[]> minmax = new java.util.LinkedHashMap<>();
        java.util.HashMap<String, Integer> dayCode = new java.util.HashMap<>();
        java.util.HashMap<String, String> dayIcon = new java.util.HashMap<>();

        for (int i = 0; i < list.length(); i++) {
            try {
                JSONObject it = list.getJSONObject(i);
                String dt = it.optString("dt_txt", "");
                if (dt.length() < 10) continue;
                String date = dt.substring(0, 10);
                JSONObject m = it.getJSONObject("main");
                float tmin = sanitizeTemperature(m.getDouble("temp_min"), metric);
                float tmax = sanitizeTemperature(m.getDouble("temp_max"), metric);
                float[] mm = minmax.get(date);
                if (mm == null) {
                    minmax.put(date, new float[]{tmin, tmax});
                } else {
                    mm[0] = Math.min(mm[0], tmin);
                    mm[1] = Math.max(mm[1], tmax);
                }
                if (dt.contains("12:00:00") || !dayCode.containsKey(date)) {
                    JSONObject wx = it.getJSONArray("weather").getJSONObject(0);
                    dayCode.put(date, wx.getInt("id"));
                    dayIcon.put(date, wx.getString("icon"));
                }
            } catch (JSONException e) {
                Log.w(TAG, "Invalid forecast entry " + i, e);
            }
        }

        int i = 0;
        for (java.util.Map.Entry<String, float[]> e : minmax.entrySet()) {
            String date = e.getKey();
            float[] mm = e.getValue();
            result.add(new DayForecast(mm[0], mm[1], "",
                    mapConditionIconToCode(dayIcon.get(date), dayCode.get(date)),
                    getDay(i), metric));
            i++;
        }
        // clients assume there are 5 entries - so fill with dummy if needed
        if (result.size() < 5) {
            for (int j = result.size(); j < 5; j++) {
                result.add(new DayForecast(0, 0, "", -1, "NaN", metric));
            }
        }
        return result;
    }

    private ArrayList<WeatherInfo.HourlyForecast> parseHourlyForecasts(JSONArray list, boolean metric) {
        ArrayList<WeatherInfo.HourlyForecast> result = new ArrayList<>();
        int count = Math.min(list.length(), 24);
        for (int i = 0; i < count; i++) {
            try {
                JSONObject hour = list.getJSONObject(i);
                JSONObject m = hour.getJSONObject("main");
                JSONObject hourWeather = hour.getJSONArray("weather").getJSONObject(0);
                JSONObject wind = hour.optJSONObject("wind");
                float hWindSpeed = wind != null ? (float) wind.optDouble("speed", Double.NaN) : Float.NaN;
                if (metric && !Float.isNaN(hWindSpeed)) {
                    hWindSpeed *= 3.6f;
                }
                result.add(new WeatherInfo.HourlyForecast(
                        sanitizeTemperature(m.getDouble("temp"), metric),
                        mapConditionIconToCode(hourWeather.getString("icon"), hourWeather.getInt("id")),
                        "",
                        hour.getLong("dt") * 1000L,
                        (float) m.optDouble("humidity", Double.NaN),
                        hWindSpeed,
                        metric));
            } catch (JSONException e) {
                Log.w(TAG, "Invalid hourly forecast for index " + i, e);
            }
        }
        return result;
    }

    // OpenWeatherMap sometimes returns temperatures in Kelvin even if we ask it
    // for deg C or deg F. Detect this and convert accordingly.
    private static float sanitizeTemperature(double value, boolean metric) {
        // threshold chosen to work for both C and F. 170 deg F is hotter
        // than the hottest place on earth.
        if (value > 170) {
            // K -> deg C
            value -= 273.15;
            if (!metric) {
                // deg C -> deg F
                value = (value * 1.8) + 32;
            }
        }
        return (float) value;
    }

    private static final HashMap<String, String> LANGUAGE_CODE_MAPPING = new HashMap<String, String>();

    static {
        LANGUAGE_CODE_MAPPING.put("bg-", "bg");
        LANGUAGE_CODE_MAPPING.put("de-", "de");
        LANGUAGE_CODE_MAPPING.put("es-", "sp");
        LANGUAGE_CODE_MAPPING.put("fi-", "fi");
        LANGUAGE_CODE_MAPPING.put("fr-", "fr");
        LANGUAGE_CODE_MAPPING.put("it-", "it");
        LANGUAGE_CODE_MAPPING.put("nl-", "nl");
        LANGUAGE_CODE_MAPPING.put("pl-", "pl");
        LANGUAGE_CODE_MAPPING.put("pt-", "pt");
        LANGUAGE_CODE_MAPPING.put("ro-", "ro");
        LANGUAGE_CODE_MAPPING.put("ru-", "ru");
        LANGUAGE_CODE_MAPPING.put("se-", "se");
        LANGUAGE_CODE_MAPPING.put("tr-", "tr");
        LANGUAGE_CODE_MAPPING.put("uk-", "ua");
        LANGUAGE_CODE_MAPPING.put("zh-CN", "zh_cn");
        LANGUAGE_CODE_MAPPING.put("zh-TW", "zh_tw");
    }

    private String getLanguageCode() {
        Locale locale = mContext.getResources().getConfiguration().locale;
        String selector = locale.getLanguage() + "-" + locale.getCountry();

        for (Map.Entry<String, String> entry : LANGUAGE_CODE_MAPPING.entrySet()) {
            if (selector.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        return "en";
    }

    private int mapConditionIconToCode(String icon, int conditionId) {

        // First, use condition ID for specific cases
        switch (conditionId) {
            // Thunderstorms
            case 202:   // thunderstorm with heavy rain
            case 232:   // thunderstorm with heavy drizzle
            case 211:   // thunderstorm
                return 4;
            case 212:   // heavy thunderstorm
                return 3;
            case 221:   // ragged thunderstorm
            case 231:   // thunderstorm with drizzle
            case 201:   // thunderstorm with rain
                return 38;
            case 230:   // thunderstorm with light drizzle
            case 200:   // thunderstorm with light rain
            case 210:   // light thunderstorm
                return 37;

            // Drizzle
            case 300:    // light intensity drizzle
            case 301:    // drizzle
            case 302:    // heavy intensity drizzle
            case 310:    // light intensity drizzle rain
            case 311:    // drizzle rain
            case 312:    // heavy intensity drizzle rain
            case 313:    // shower rain and drizzle
            case 314:    // heavy shower rain and drizzle
            case 321:    // shower drizzle
                return 9;

            // Rain
            case 500:    // light rain
            case 501:    // moderate rain
            case 520:    // light intensity shower rain
            case 521:    // shower rain
            case 531:    // ragged shower rain
                return 11;
            case 502:    // heavy intensity rain
            case 503:    // very heavy rain
            case 504:    // extreme rain
            case 522:    // heavy intensity shower rain
                return 12;
            case 511:    // freezing rain
                return 10;

            // Snow
            case 600:
            case 620:
                return 14; // light snow
            case 601:
            case 621:
                return 16; // snow
            case 602:
            case 622:
                return 41; // heavy snow
            case 611:
            case 612:
            case 613:
                return 18; // sleet (incl. shower sleet)
            case 615:
            case 616:
                return 5;  // rain and snow

            // Atmosphere
            case 741:    // fog
                return 20;
            case 711:    // smoke
            case 762:    // volcanic ash
                return 22;
            case 701:    // mist
            case 721:    // haze
                return 21;
            case 731:    // sand/dust whirls
            case 751:    // sand
            case 761:    // dust
                return 19;
            case 771:    // squalls
                return 23;
            case 781:    // tornado
                return 0;

            // clouds
            case 800:     // clear sky
                return icon.endsWith("n") ? 31 : 32; // day or night
            case 801:     // few clouds
                return icon.endsWith("n") ? 33 : 34; // day or night
            case 802:     // scattered clouds
                return icon.endsWith("n") ? 27 : 28; // day or night
            case 803:     // broken clouds
            case 804:     // overcast clouds
                return icon.endsWith("n") ? 29 : 30; // day or night

            // Extreme
            case 900:
                return 0;  // tornado
            case 901:
                return 1;  // tropical storm
            case 902:
                return 2;  // hurricane
            case 903:
                return 25; // cold
            case 904:
                return 36; // hot
            case 905:
                return 24; // windy
            case 906:
                return 17; // hail
        }

        return -1;
    }

    private void loadKeys() {
        try {
            String key = mContext.getResources().getString(R.string.owm_api_key_1);
            if (!TextUtils.isEmpty(key)) {
                mKeys.add(key);
            }
        } catch (Resources.NotFoundException e) {
        }
        try {
            String key = mContext.getResources().getString(R.string.owm_api_key_2);
            if (!TextUtils.isEmpty(key)) {
                mKeys.add(key);
            }
        } catch (Resources.NotFoundException e) {
        }
        log(TAG, "use API keys = " + mKeys);
    }

    private String getAPIKey() {
        String customKey = Config.getOwmKey(mContext);
        if (!TextUtils.isEmpty(customKey)) {
            return customKey;
        }
        if (mKeys.size() > 0) {
            int key = mRequestNumber % mKeys.size();
            log(TAG, "use API key = " + key);
            return mKeys.get(key);
        }
        try {
            return mContext.getResources().getString(R.string.owm_api_key);
        } catch (Resources.NotFoundException e) {
        }
        return null;
    }

    public boolean shouldRetry() {
        return false;
    }
}
