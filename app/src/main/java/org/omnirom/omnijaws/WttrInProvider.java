/*
 * Copyright (C) 2026 IchthysOS
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
import java.util.Calendar;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnirom.omnijaws.WeatherInfo.DayForecast;

import android.content.Context;
import android.location.Location;
import android.util.Log;

/**
 * wttr.in (World Weather Online data) keyless provider. Independent redundancy alongside
 * Open-Meteo and MET Norway. wttr.in returns WWO condition codes which we map to the same
 * Yahoo-style codes the icon packs use.
 */
public class WttrInProvider extends AbstractWeatherProvider {
    private static final String TAG = "WttrInProvider";

    private static final String URL_WEATHER =
            "https://wttr.in/%f,%f?format=j1&lang=en";

    public WttrInProvider(Context context) {
        super(context);
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return handleWeatherRequest(id, metric);
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES,
                location.getLatitude(), location.getLongitude());
        return handleWeatherRequest(coordinates, metric);
    }

    private WeatherInfo handleWeatherRequest(String selection, boolean metric) {
        double[] coords = parseCoords(selection);
        if (coords == null) {
            Log.w(TAG, "could not parse coordinates from '" + selection + "'");
            return null;
        }
        String url = String.format(Locale.US, URL_WEATHER, coords[0], coords[1]);
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject root = new JSONObject(response);
            JSONObject cur = root.getJSONArray("current_condition").getJSONObject(0);
            JSONArray days = root.getJSONArray("weather");

            boolean isDay = isDayNow();
            int wwo = cur.getInt("weatherCode");
            float temp = (float) cur.getDouble(metric ? "temp_C" : "temp_F");
            float humidity = (float) cur.getDouble("humidity");
            float wind = (float) cur.getDouble(metric ? "windspeedKmph" : "windspeedMiles");
            int windDir = cur.optInt("winddirDegree", 0);

            String city = getWeatherDataLocality(selection);

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ selection,
                    /* cityId */ city,
                    /* condition */ "",
                    /* conditionCode */ mapWwoToCode(wwo, isDay),
                    /* temperature */ temp,
                    /* humidity */ humidity,
                    /* wind */ wind,
                    /* windDir */ windDir,
                    metric,
                    parseForecasts(days, metric),
                    System.currentTimeMillis());

            String feelsKey = metric ? "FeelsLikeC" : "FeelsLikeF";
            if (cur.has(feelsKey)) {
                w.setFeelsLike((float) cur.getDouble(feelsKey));
            }
            if (cur.has("pressure")) {
                w.setPressure((float) cur.getDouble("pressure"));
            }
            if (cur.has("uvIndex")) {
                w.setUvi((float) cur.optDouble("uvIndex", Double.NaN));
            }
            if (cur.has("visibility")) {
                float visKm = (float) cur.getDouble("visibility"); // wttr reports km
                w.setVisibility(metric ? visKm : visKm / 1.60934f);
            }

            long[] sun = parseSunTimes(days);
            if (sun != null) {
                w.setSunrise(sun[0]);
                w.setSunset(sun[1]);
            }

            w.setHourlyForecasts(parseHourly(days, metric));

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (selection = " + selection + ")", e);
        }
        return null;
    }

    private ArrayList<DayForecast> parseForecasts(JSONArray days, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<>();
        int count = days.length();
        for (int i = 0; i < count; i++) {
            try {
                JSONObject day = days.getJSONObject(i);
                float high = (float) day.getDouble(metric ? "maxtempC" : "maxtempF");
                float low = (float) day.getDouble(metric ? "mintempC" : "mintempF");
                // midday (~12:00) hourly entry gives the representative daytime condition
                int wwo = 113;
                JSONArray hourly = day.optJSONArray("hourly");
                if (hourly != null && hourly.length() > 0) {
                    int mid = Math.min(4, hourly.length() - 1); // wttr hourly is 3-hourly (index 4 ~= 12:00)
                    wwo = hourly.getJSONObject(mid).getInt("weatherCode");
                }
                result.add(new DayForecast(
                        low, high, "", mapWwoToCode(wwo, true), getDay(i), metric));
            } catch (JSONException e) {
                Log.w(TAG, "Invalid forecast for day " + i, e);
            }
        }
        if (result.size() < 5) {
            for (int i = result.size(); i < 5; i++) {
                result.add(new DayForecast(0, 0, "", -1, "NaN", metric));
            }
        }
        return result;
    }

    private ArrayList<WeatherInfo.HourlyForecast> parseHourly(JSONArray days, boolean metric) {
        ArrayList<WeatherInfo.HourlyForecast> result = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            for (int d = 0; d < days.length() && result.size() < 24; d++) {
                JSONObject day = days.getJSONObject(d);
                String date = day.getString("date");
                JSONArray hourly = day.optJSONArray("hourly");
                if (hourly == null) continue;
                for (int h = 0; h < hourly.length() && result.size() < 24; h++) {
                    JSONObject ho = hourly.getJSONObject(h);
                    int minutes = ho.optInt("time", 0); // "0","300",...,"2100" -> HHmm as int
                    long ts = hourTimestamp(date, minutes);
                    if (ts < now - 3 * 3600_000L) continue; // skip well-past hours
                    boolean isDay = (minutes / 100) >= 6 && (minutes / 100) < 20;
                    result.add(new WeatherInfo.HourlyForecast(
                            (float) ho.getDouble(metric ? "tempC" : "tempF"),
                            mapWwoToCode(ho.getInt("weatherCode"), isDay),
                            "",
                            ts,
                            (float) ho.optDouble("humidity", Double.NaN),
                            (float) ho.optDouble(metric ? "windspeedKmph" : "windspeedMiles", Double.NaN),
                            metric));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Invalid hourly forecast data", e);
        }
        return result;
    }

    private static long hourTimestamp(String date, int hhmm) {
        try {
            String[] p = date.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]),
                    hhmm / 100, hhmm % 100, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private long[] parseSunTimes(JSONArray days) {
        try {
            JSONObject astro = days.getJSONObject(0).getJSONArray("astronomy").getJSONObject(0);
            String date = days.getJSONObject(0).getString("date");
            long sr = parseClock(date, astro.getString("sunrise"));
            long ss = parseClock(date, astro.getString("sunset"));
            if (sr > 0 && ss > 0) return new long[]{sr, ss};
        } catch (Exception ignore) {
        }
        return null;
    }

    // "05:12 AM" -> epoch millis on the given date
    private static long parseClock(String date, String clock) {
        try {
            String[] dp = date.split("-");
            String[] parts = clock.trim().split("[: ]");
            int hour = Integer.parseInt(parts[0]);
            int min = Integer.parseInt(parts[1]);
            String ampm = parts[2].toUpperCase(Locale.US);
            if (ampm.equals("PM") && hour != 12) hour += 12;
            if (ampm.equals("AM") && hour == 12) hour = 0;
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(dp[0]), Integer.parseInt(dp[1]) - 1, Integer.parseInt(dp[2]),
                    hour, min, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isDayNow() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 20;
    }

    private static double[] parseCoords(String selection) {
        try {
            int latStart = selection.indexOf("lat=") + 4;
            int amp = selection.indexOf("&", latStart);
            int lonStart = selection.indexOf("lon=") + 4;
            double lat = Double.parseDouble(selection.substring(latStart, amp));
            double lon = Double.parseDouble(selection.substring(lonStart));
            return new double[]{lat, lon};
        } catch (Exception e) {
            return null;
        }
    }

    // WWO weather code -> Yahoo-style condition code used by the icon packs.
    private static int mapWwoToCode(int wwo, boolean isDay) {
        switch (wwo) {
            case 113: return isDay ? 32 : 31;              // sunny / clear
            case 116: return isDay ? 30 : 29;              // partly cloudy
            case 119:                                       // cloudy
            case 122: return 26;                            // overcast
            case 143:
            case 248:
            case 260: return 20;                            // mist / fog
            case 176:
            case 263:
            case 266:
            case 293:
            case 296:
            case 299:
            case 302:
            case 353:
            case 356: return 11;                            // rain / showers
            case 305:
            case 308:
            case 359: return 12;                            // heavy rain
            case 179:
            case 227:
            case 323:
            case 326:
            case 368: return 14;                            // light snow
            case 230:
            case 329:
            case 332:
            case 335:
            case 338:
            case 371: return 16;                            // snow
            case 182:
            case 185:
            case 281:
            case 284:
            case 311:
            case 314:
            case 317:
            case 320:
            case 350:
            case 362:
            case 365:
            case 374:
            case 377: return 18;                            // sleet / freezing / ice
            case 200:
            case 386:
            case 389:
            case 392:
            case 395: return 4;                             // thunder
            default:  return 26;                            // safe fallback: cloudy
        }
    }

    public boolean shouldRetry() {
        return false;
    }
}
