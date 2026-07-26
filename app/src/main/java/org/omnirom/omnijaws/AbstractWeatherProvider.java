/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.omnirom.omnijaws;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractWeatherProvider {
    private static final String TAG = "AbstractWeatherProvider";
    private static final boolean DEBUG = false;
    protected Context mContext;
    private static final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final String URL_PLACES =
            "https://secure.geonames.org/searchJSON?name_startsWith=%s&lang=%s&username=omnijaws&maxRows=20";
    // IchthysOS: the geonames "omnijaws" account is dead (404). Use OpenStreetMap's keyless
    // Nominatim reverse-geocoder instead (needs a valid User-Agent, which NetworkUtils sets).
    private static final String URL_LOCALITY =
            "https://nominatim.openstreetmap.org/reverse?lat=%f&lon=%f&format=json&zoom=10&accept-language=%s";
    protected static final String PART_COORDINATES =
            "lat=%f&lon=%f";

    public AbstractWeatherProvider(Context context) {
        mContext = context;
    }

    protected String retrieve(String url) {
        return NetworkUtils.downloadUrlMemoryAsString(url);
    }

    public abstract WeatherInfo getCustomWeather(String id, boolean metric);

    public abstract WeatherInfo getLocationWeather(Location location, boolean metric);

    public abstract boolean shouldRetry();

    protected void log(String tag, String msg) {
        if (DEBUG) Log.d("WeatherService:" + tag, msg);
    }

    private String getCoordinatesLocalityWithGoogle(String coordinate) {
        double latitude = Double.valueOf(coordinate.substring(4, coordinate.indexOf("&")));
        double longitude = Double.valueOf(coordinate.substring(coordinate.indexOf("lon=") + 4));
        Geocoder geocoder = new Geocoder(mContext.getApplicationContext(), Locale.getDefault());
        try {
            List<Address> listAddresses = geocoder.getFromLocation(latitude, longitude, 1);
            if(listAddresses != null && listAddresses.size() > 0){
                Address a = listAddresses.get(0);
                return TextUtils.isEmpty(a.getLocality()) ? a.getAdminArea() : a.getLocality();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    protected String getCoordinatesLocality(String coordinate) {
        String cityGoogle = getCoordinatesLocalityWithGoogle(coordinate);
        if (!TextUtils.isEmpty(cityGoogle)) {
            return cityGoogle;
        }
        double latitude = Double.valueOf(coordinate.substring(4, coordinate.indexOf("&")));
        double longitude = Double.valueOf(coordinate.substring(coordinate.indexOf("lon=") + 4));
        Log.d(TAG, "getCoordinatesLocality " + latitude + " " + longitude);

        String lang = Locale.getDefault().getLanguage().replaceFirst("_", "-");
        String url = String.format(Locale.US, URL_LOCALITY, latitude, longitude, lang);
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject jsonResults = new JSONObject(response);
            // IchthysOS: Nominatim reverse-geocode response -> address{city|town|village|...}.
            if (jsonResults.has("address")) {
                JSONObject address = jsonResults.getJSONObject("address");
                for (String key : new String[] {
                        "city", "town", "village", "municipality",
                        "suburb", "county", "state" }) {
                    String name = address.optString(key, "");
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Received malformed location data (coordinate=" + coordinate + ")", e);
        }
        return null;
    }

    protected String getWeatherDataLocality(String coordinates) {
        String city;
        if (Config.isCustomLocation(mContext)) {
            city = Config.getLocationName(mContext);
            if (TextUtils.isEmpty(city)) {
                city = getCoordinatesLocality(coordinates);
            }
        } else {
            city = getCoordinatesLocality(coordinates);
        }
        if (TextUtils.isEmpty(city)) {
            city = mContext.getResources().getString(R.string.omnijaws_city_unkown);
        }
        log(TAG, "getWeatherDataLocality = " + city);
        return city;
    }

    protected String getDay(int i) {
        Calendar calendar = Calendar.getInstance();
        if (i > 0) {
            calendar.add(Calendar.DATE, i);
        }
        return dayFormat.format(calendar.getTime());
    }
}
