package com.pepe.mapaconruta;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.OnTokenCanceledListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.ItemizedOverlayWithFocus;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    MapView map = null;
    private MyLocationNewOverlay mLocationOverlay;
    private RotationGestureOverlay mRotationGestureOverlay;
    private RequestQueue queue;
    private JsonObjectRequest requestMapRequest;
    private CompassOverlay mCompassOverlay;
    private Button btnPosition, btnDeletePosition;
    private FusedLocationProviderClient fusedLocationClient;
    private final double[] currentPos = new double[5];

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //handle permissions first, before map is created. not depicted here

        //load/initialize the osmdroid configuration, this can be done
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        //setting this before the layout is inflated is a good idea
        //it 'should' ensure that the map has a writable location for the map cache, even without permissions
        //if no tiles are displayed, you can try overriding the cache path using Configuration.getInstance().setCachePath
        //see also StorageUtils
        //note, the load method also sets the HTTP User Agent to your application's package name, abusing osm's tile servers will get you banned based on this string

        //inflate and create the map
        setContentView(R.layout.activity_main);

        map = (MapView) findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);

        map.setMultiTouchControls(true);

        validarPermisos();

        IMapController mapController = map.getController();
        mapController.setZoom(19.0);
        GeoPoint startPoint = new GeoPoint(20.12447804102295, -101.19173216026564);
        mapController.setCenter(startPoint);

        this.mLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(ctx
        ),map);
        this.mLocationOverlay.enableMyLocation();
        map.getOverlays().add(this.mLocationOverlay);

        mRotationGestureOverlay = new RotationGestureOverlay(ctx, map);
        mRotationGestureOverlay.setEnabled(true);
        map.getOverlays().add(this.mRotationGestureOverlay);

        //your items
        ArrayList<OverlayItem> items = new ArrayList<OverlayItem>();
        items.add(new OverlayItem("Title", "Description", new GeoPoint(20.12447804102295d,
                -101.19173216026564d))); // Lat/Lon decimal degrees

        //the overlay
        ItemizedOverlayWithFocus<OverlayItem> mOverlay = new ItemizedOverlayWithFocus<OverlayItem>(items,
                new ItemizedIconOverlay.OnItemGestureListener<OverlayItem>() {
                    @Override
                    public boolean onItemSingleTapUp(final int index, final OverlayItem item) {
                        //do something
                        return true;
                    }
                    @Override
                    public boolean onItemLongPress(final int index, final OverlayItem item) {
                        return false;
                    }
                }, ctx);
        mOverlay.setFocusItemsOnTap(true);

        btnPosition = findViewById(R.id.btnPos);
        btnPosition.setOnClickListener(view -> {
            GeoPoint geoPoint = new GeoPoint(currentPos[0], currentPos[1]);
            mapController.setCenter(geoPoint);
            obtenerRouteFromMapRequest(geoPoint);
        });

        btnDeletePosition = findViewById(R.id.btnDelete);
        btnDeletePosition.setOnClickListener(view -> {
            map.getOverlays().remove(map.getOverlays().size() - 1);
            map.getOverlays().remove(map.getOverlays().size() - 1);

        });
        LastLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        map.onResume(); //needed for compass, my location overlays, v6.0.0 and up
        LastLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().save(this, prefs);
        map.onPause();  //needed for compass, my location overlays, v6.0.0 and up
        LastLocation();
    }

    private void obtenerRouteFromMapRequest(GeoPoint geoPoint) {
        List<GeoPoint> puntosRuta = new ArrayList<>();
        queue =
                Volley.newRequestQueue(this);

        requestMapRequest =
                new JsonObjectRequest(
                        "http://www.mapquestapi.com/directions/v2/route?key=3DAfWsMIWwkEfW0XdH9hU6HufelHBhI1&from=" + geoPoint.getLatitude() + "," + geoPoint.getLongitude() + "&to=20.12447804102295, -101.19173216026564",
                        new Response.Listener<JSONObject>() {
                            @Override
                            public void onResponse(JSONObject response) {
                                Log.d("PEPE", "se ejecuto");
                                try {
                                    JSONArray indicaiones = response.getJSONObject("route")
                                            .getJSONArray("legs")
                                            .getJSONObject(0).
                                                    getJSONArray("maneuvers");


                                    for (int i = 0; i < indicaiones.length(); i++) {
                                        JSONObject indi = indicaiones.getJSONObject(i);
                                        String strlatlog = indi.getJSONObject("startPoint").get("lat").toString()
                                                + "," +
                                                indi.getJSONObject("startPoint").get("lng").toString();


                                        Log.d("PEPE", "se ejecuto: " + strlatlog);
                                        puntosRuta.add(new GeoPoint(Double.parseDouble(indi.getJSONObject("startPoint").get("lat").toString()),
                                                Double.parseDouble(indi.getJSONObject("startPoint").get("lng").toString())));

                                    }

                                    DrawPolilinea(puntosRuta);

                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        },
                        new Response.ErrorListener() {
                            @Override
                            public void onErrorResponse(VolleyError error) {
                                Log.d("PEPE", "se ejecuto CON ERROR");

                            }
                        }
                );

        queue.add(requestMapRequest);
    }

    private void DrawPolilinea(List<GeoPoint> geoPoints) {
        GeoPoint startPoint = new GeoPoint(currentPos[0], currentPos[1]);

        Marker startMarker = new Marker(map);
        startMarker.setPosition(startPoint);
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(startMarker);

        Polyline line = new Polyline();
        line.setPoints(geoPoints);
        line.setOnClickListener(new Polyline.OnClickListener() {
            @Override
            public boolean onClick(Polyline polyline, MapView mapView, GeoPoint eventPos) {
                Toast.makeText(mapView.getContext(), "polyline with " + polyline.getPoints().size() + "pts was tapped", Toast.LENGTH_LONG).show();
                return false;
            }
        });
        map.getOverlayManager().add(line);

        IMapController mapController = map.getController();
        mapController.setZoom(17.0);
        mapController.setCenter(startPoint);

    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void validarPermisos() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
                &&
                ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            // You can use the API that requires the permission.
            solicitarPermiso();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void solicitarPermiso() {
        ActivityResultLauncher<String[]> locationPermissionRequest =

                registerForActivityResult(new ActivityResultContracts
                                .RequestMultiplePermissions(),
                        result -> {
                            Boolean fineLocationGranted = result.getOrDefault(
                                    Manifest.permission.ACCESS_FINE_LOCATION, false);
                            Boolean coarseLocationGranted = result.getOrDefault(
                                    Manifest.permission.ACCESS_COARSE_LOCATION, false);

                        }
                );

        // Before you perform the actual permission request, check whether your app
        // already has the permissions, and whether your app needs to show a permission
        // rationale dialog. For more details, see Request permissions.
        locationPermissionRequest.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });

    }

    private void LastLocation() {

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {

            @Override
            public void onSuccess(Location location) {
                // Got last known location. In some rare situations this can be null.
                if (location != null) {
                    // Logic to handle location object
                    String loca = location.getLatitude() + ", " + location.getLongitude();
                    Log.d("UBIX", loca);
                    Toast.makeText(getApplicationContext(), loca,
                            Toast.LENGTH_SHORT).show();
                    currentPos[0] = location.getLatitude();
                    currentPos[1] = location.getLongitude();
                }
            }
        });

        fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY,
                new CancellationToken() {
                    @NonNull
                    @Override
                    public CancellationToken onCanceledRequested(@NonNull OnTokenCanceledListener onTokenCanceledListener) {
                        return null;
                    }

                    @Override
                    public boolean isCancellationRequested() {
                        return false;
                    }
                }
        ).addOnSuccessListener(this, new OnSuccessListener<Location>() {
            @Override
            public void onSuccess(Location location) {
                // Got last known location. In some rare situations this can be null.
                if (location != null) {
                    // Logic to handle location object
                    String loca = location.getLatitude() + ", " + location.getLongitude();
                    Log.d("UBIXC", loca);
                    Toast.makeText(getApplicationContext(), loca,
                            Toast.LENGTH_SHORT).show();
                    currentPos[0] = location.getLatitude();
                    currentPos[1] = location.getLongitude();
                }
            }
        });

        return;
    }
}