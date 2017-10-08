package edu.vanderbilt.repugraph;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.TextView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.ImageRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.firebase.messaging.FirebaseMessaging;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.BeaconTransmitter;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.service.RunningAverageRssiFilter;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;
import de.hdodenhof.circleimageview.CircleImageView;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.RuntimePermissions;


@RuntimePermissions
public class MainActivity extends Activity implements BeaconConsumer {
    static final String PREFIX = "37f8a4b0-38c7-b8f8-79ca-00";

    @BindView(R.id.phoneView)
    TextView phoneView;

    @BindView(R.id.selectedProfile)
    CircleImageView selectedProfile;

    @BindView(R.id.selectedName)
    TextView selectedName;

    @BindView(R.id.others)
    LinearLayout others;

    @BindView(R.id.minicircle1)
    CircleImageView minicircle1;

    @BindView(R.id.minicircle2)
    CircleImageView minicircle2;

    @BindView(R.id.minicircle3)
    CircleImageView minicircle3;

    @BindView(R.id.minicircle4)
    CircleImageView minicircle4;

    @BindView(R.id.ratings)
    RatingBar ratings;

    List<CircleImageView> minicircles;

    String mPhoneNumber;

    BeaconManager beaconManager;
    BeaconTransmitter beaconTransmitter;

    Handler handler;
    Map<String, FlagBasedRange> averages;

    private Runnable cleanupTask;
    private RequestQueue rq;

    private Map<String, String> names = new HashMap<>();

    private LruCache<String, Bitmap> bitmapCache;

    private String userSelectOverride;
    private int myViewTag = R.id.myTag;

    private String currentlySelected;

    private View.OnClickListener onPicClick = view -> {
        if (view.equals(selectedProfile)) {
            if (userSelectOverride != null) {
                userSelectOverride = null;
            }
        } else {
            CircleImageView civ = (CircleImageView) view;
            for (Map.Entry<String, Bitmap> entry : bitmapCache.snapshot().entrySet()) {
                if (entry.getValue().equals(((BitmapDrawable) civ.getDrawable()).getBitmap())) {
                    userSelectOverride = entry.getKey();
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler();
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        rq = Volley.newRequestQueue(this);
        minicircles = Arrays.asList(minicircle1, minicircle2, minicircle3, minicircle4);

        selectedProfile.setOnClickListener(onPicClick);
        for (CircleImageView minicircle : minicircles) {
            minicircle.setOnClickListener(onPicClick);
        }
        ratings.setOnRatingBarChangeListener((ratingBar, v, b) -> {
            if (v == 0.0) return;
            int r = (int) v;
            if (currentlySelected != null) {
                StringRequest sr = new StringRequest(Request.Method.GET,
                        generateRateUrl(currentlySelected, r), (res) -> {}, (err) -> {});
                rq.add(sr);
            }
            ratingBar.setRating(0.0F);
        });

        bitmapCache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        MainActivityPermissionsDispatcher.renderPhoneNumberWithCheck(this, () -> {
            verifyBluetooth();
            MainActivityPermissionsDispatcher.startBluetoothWithCheck(this, () -> {
                averages = new HashMap<>();
                cleanupTask = () -> {
                    Iterator<Map.Entry<String, FlagBasedRange>> itr = averages.entrySet().iterator();
                    while (itr.hasNext()) {
                        Map.Entry<String, FlagBasedRange> entry = itr.next();
                        FlagBasedRange range = entry.getValue();
                        if (range.getFlag() == 0) {
                            if (range.timeSinceLastRecv() > 4400L) {
                                log("First flag for " + entry.getKey());
                                range.incrementFlag();
                            }
                        } else if (range.getFlag() == 1) {
                            if (range.timeSinceLastRecv() > 3100L) {
                                log("Removing " + entry.getKey() + " (out of range)");
                                itr.remove();
                            }
                        }
                    }

                    List<String> displayList = new ArrayList<>(averages.keySet());
                    // displayList.add("1234567890");
                    // displayList.add("7322138440");
                    Collections.sort(displayList, (String o1, String o2) -> {
                        Double distance1 = distances.get(o1);
                        if (distance1 == null) {
                            distance1 = 100D;
                        }
                        Double distance2 = distances.get(o2);
                        if (distance2 == null) {
                            distance2 = 100D;
                        }
                        return Double.compare(distance1, distance2);
                    });

                    if (userSelectOverride != null) {
                        displayList.remove(userSelectOverride);
                        displayList.add(0, userSelectOverride);
                        selectedProfile.setBorderColorResource(R.color.ratingColor);
                    } else {
                        selectedProfile.setBorderColor(Color.WHITE);
                    }

                    if (displayList.size() == 0) {
                        selectedProfile.setImageResource(R.drawable.unavailable);
                        selectedName.setText("Searching...");
                        others.setVisibility(View.GONE);
                        currentlySelected = null;
                    } else {
                        String primaryId = displayList.get(0);
                        currentlySelected = primaryId;
                        Bitmap bm = bitmapCache.get(primaryId);
                        String name = names.get(primaryId);
                        if (bm != null) {
                            selectedProfile.setImageBitmap(bm);
                            selectedProfile.setTag(myViewTag, primaryId);
                            selectedName.setText(name);
                        } else {
                            selectedProfile.setImageResource(R.drawable.temp);
                            requestImageLoad(primaryId);
                            selectedName.setText(name);
                        }
                        if (displayList.size() == 1) {
                            others.setVisibility(View.GONE);
                        } else {
                            others.setVisibility(View.VISIBLE);
                            for (int i = 0; i < minicircles.size(); i++) {
                                int listIndex = 1 + i;
                                CircleImageView circle = minicircles.get(i);
                                try {
                                    String devId = displayList.get(listIndex);
                                    Bitmap bm2 = bitmapCache.get(devId);
                                    if (bm2 != null) {
                                        circle.setImageBitmap(bm2);
                                        circle.setVisibility(View.VISIBLE);
                                        circle.setTag(myViewTag, bm2);
                                    } else {
                                        selectedProfile.setImageResource(R.drawable.temp);
                                        requestImageLoad(devId);
                                    }
                                } catch (Exception e) {
                                    circle.setVisibility(View.GONE);
                                }
                            }
                        }
                    }

                    if (displayList.size() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (String device : displayList) {
                            if (!names.containsKey(device)) {
                                StringRequest sr = new StringRequest(Request.Method.GET,
                                        generateNameReqUrl(device),
                                        (res) -> {
                                            names.put(device, res.trim());
                                        }, (err) -> {
                                });
                                rq.add(sr);
                                sb.append(device).append('\n');
                            } else {
                                sb.append(device).append(" (").append(names.get(device)).append(")").append("\n");
                            }
                        }
                       // nearbyDevices.setText(sb.toString());
                    } else {
                        // nearbyDevices.setText("No devices");
                    }

                    handler.postDelayed(cleanupTask, 100L);
                };
                cleanupTask.run();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        beaconManager.unbind(this);
        beaconTransmitter.stopAdvertising();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // NOTE: delegate the permission handling to generated method
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    private void verifyBluetooth() {
        try {
            if (!BeaconManager.getInstanceForApplication(this).checkAvailability()) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Bluetooth not enabled");
                builder.setMessage("Please enable bluetooth in settings and restart this application.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(dialog -> {
                    finish();
                    System.exit(0);
                });
                builder.show();
            }
        } catch (RuntimeException e) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Bluetooth LE not available");
            builder.setMessage("Sorry, this device does not support Bluetooth LE.");
            builder.setPositiveButton(android.R.string.ok, null);
            builder.setOnDismissListener(dialog -> {
                finish();
                System.exit(0);
            });
            builder.show();
        }
    }

    @NeedsPermission(Manifest.permission.READ_SMS)
    public void renderPhoneNumber(Runnable cb) {
        TelephonyManager tMgr = (TelephonyManager) getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneNumber = tMgr.getLine1Number();
        mPhoneNumber = mPhoneNumber.substring(mPhoneNumber.length() - 10);
        FirebaseMessaging.getInstance().subscribeToTopic(mPhoneNumber);

        if (cb != null) cb.run();
    }

    @NeedsPermission({Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE})
    public void startBluetooth(Runnable cb) {
        StringRequest sr = new StringRequest(Request.Method.GET,
                generateNameReqUrl(mPhoneNumber),
                (res) -> {
                    log("Starting transmitter...");
                    String uuid = PREFIX + mPhoneNumber;
                    Beacon beacon = new Beacon.Builder()
                            .setId1(uuid)
                            .setId2("1")
                            .setId3("2")
                            .setManufacturer(0x0118)
                            .setTxPower(-66)
                            .setDataFields(Collections.singletonList(0L))
                            .build();
                    BeaconParser beaconParser = new BeaconParser()
                            .setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25");
                    BeaconTransmitter beaconTransmitter = new BeaconTransmitter(getApplicationContext(), beaconParser);
                    beaconTransmitter.setAdvertiseTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM);
                    beaconTransmitter.startAdvertising(beacon);
                    beaconTransmitter.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY);

                    phoneView.setText(mPhoneNumber + " (" + res.trim() + ")");
                    names.put(mPhoneNumber, res.trim());

                    phoneView.setText(mPhoneNumber + " (" + uuid + ")");

                    beaconManager = BeaconManager.getInstanceForApplication(this);
                    BeaconManager.setRssiFilterImplClass(RunningAverageRssiFilter.class);
                    RunningAverageRssiFilter.setSampleExpirationMilliseconds(5000L);
                    beaconManager.getBeaconParsers().add(beaconParser);
                    beaconManager.bind(this);

                    if (cb != null) cb.run();
                },
                (err) -> {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Type your name");

                    final EditText input = new EditText(this);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    builder.setView(input);
                    builder.setPositiveButton("Register", (dialog, which) -> {
                        String name = input.getText().toString();
                        String regUrl = null;
                        try {
                            regUrl = generateRegisterUrl(mPhoneNumber, name);
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                        Log.i("YLREG", regUrl);
                        StringRequest sr2 = new StringRequest(Request.Method.GET,
                                regUrl, (res) -> startBluetooth(cb),
                                (err2) -> {});
                        rq.add(sr2);
                    });

                    builder.show();
                }
        );
        rq.add(sr);
    }

    TimeExpiringLruCache<String, Double> distances = new TimeExpiringLruCache<>(50, 5000);

    @Override
    public void onBeaconServiceConnect() {
        beaconManager.addRangeNotifier((beacons, region) -> {
            if (beacons.size() > 0) {
                for (Beacon beacon : beacons) {
                    String id = beacon.getId1().toString();
                    if (id.startsWith(PREFIX)) {
                        id = id.substring(PREFIX.length());
                        FlagBasedRange avg;
                        if (averages.containsKey(id)) {
                            avg = averages.get(id);
                        } else {
                            avg = new FlagBasedRange();
                            averages.put(id, avg);
                        }
                        log("Saw " + id +
                                ", last time " + avg.timeSinceLastRecv());
                        avg.recordRecv();
                        distances.put(id, beacon.getDistance());
                    }
                }
            }
/*
            Map<String, Double> distanceSnapshot = distances.snapshot();
            Iterator<Map.Entry<String, Double>> itr = distanceSnapshot.entrySet().iterator();
            while (itr.hasNext()) {
                if (distances.get(itr.next().getKey()) == null) {
                    itr.remove();
                }
            }

            StringBuilder sb = new StringBuilder();

            if (distanceSnapshot.size() == 0) {
                sb.append("No devices");
            } else {
                for (Map.Entry<String, Double> entry : distanceSnapshot.entrySet()) {
                    sb.append(entry.getKey()).append(" @ ").append(entry.getValue()).append("m").append('\n');
                }
            }
            runOnUiThread(() -> nearbyDevices.setText(sb.toString()));
            */
        });

        try {
            beaconManager.startRangingBeaconsInRegion(new Region("myRangingUniqueId", null, null, null));
        } catch (RemoteException e) {
        }
    }

    public void log(String msg) {
        Log.i("REPUGRAPH_BLE", msg);
    }

    private String generateNameReqUrl(String id) {
        return "http://jet.fuel.cant.melt.da.nkmem.es:3000/" + id + "/name.txt";
    }

    private String generateImageReqUrl(String id) {
        return "http://jet.fuel.cant.melt.da.nkmem.es:3000/" + id + "/avatar.jpg";
    }

    private String generateRegisterUrl(String id, String name) throws UnsupportedEncodingException {
        return "http://jet.fuel.cant.melt.da.nkmem.es:3000/register/" + id + "/" + Uri.encode(name);
    }

    private String generateRateUrl(String id, int rating) {
        return "http://jet.fuel.cant.melt.da.nkmem.es:3000/rate/" + mPhoneNumber + "/" + id + "/" + rating;
    }

    private void requestImageLoad(String id) {
        ImageRequest ir = new ImageRequest(generateImageReqUrl(id),
                (res) -> bitmapCache.put(id, res), 0, 0, null, (err) -> {
        });
        rq.add(ir);
    }
}
