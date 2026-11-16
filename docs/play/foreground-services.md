# Foreground service declarations (draft)

Source: `android/app/src/main/AndroidManifest.xml`. Statements marked CHECK are not confirmed in code.

## SliceService (`specialUse`, manifest line 87)

- Manifest subtype text (line 93): "Slicing a 3D model into printer G-code, started by the user; takes up to several minutes."
- Justification for the form: The user taps Slice. The app runs the slicing engine on the device and shows an ongoing notification so Android does not stop the work when the app goes to the background. A large model can take several minutes. The service stops when slicing ends or the user cancels. CHECK: that it stops itself on cancel and on failure.
- Why no standard type fits: slicing is local computation. It is not media, location, data sync or a connected device. CHECK: that `dataSync` was considered and rejected on purpose.
- User-visible: a notification during slicing. CHECK: notification text and cancel action.
- Video for the review: a short screen recording of Slice, then leaving the app, then the notification. CHECK: not recorded yet.

## PrintMonitorService (`connectedDevice`, manifest line 110)

- Justification for the form: After the user sends a print, the service keeps the connection to the user's own 3D printer on the local network (or its host) so print progress and errors reach the notification and widget while the app is in the background. It runs only while a print is being watched. CHECK: that it stops when the print ends or the user stops monitoring.
- Why `connectedDevice`: it keeps a link with an external device the user has set up. CHECK: Play may expect a Bluetooth, USB or network-device interaction; confirm the local-network case is accepted for this type.
- Permissions used: `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `ACCESS_LOCAL_NETWORK`, `POST_NOTIFICATIONS`. CHECK: whether `CHANGE_WIFI_MULTICAST_STATE` (printer discovery) belongs in the declaration.
- Video for the review: send a print, leave the app, show progress in the notification. CHECK: not recorded yet.

## Not a foreground service

- `ScadService` (own process `:scad`, no foreground type): CHECK that it never calls `startForeground`.
