/* Copyright 2013 Alex Burka

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
package org.durka.hallmonitor;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;
import eu.chainfire.libsuperuser.Shell;

public class PreferenceFragmentLoader extends PreferenceFragment implements
		SharedPreferences.OnSharedPreferenceChangeListener {
	private final String LOG_TAG = "Hall.PFL";

	private boolean mDebug = false;
	private int mAboutClicked = 0;
	private final int mAboutClickCount = 7;

	private CoreStateManager mStateManager;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Log.d(LOG_TAG + ".oC", "");

		mStateManager = ((CoreApp) getActivity().getApplicationContext())
				.getStateManager();

		try {
			final String resourceName = getArguments()
					.getString("resource", "");
			Log.d(LOG_TAG + ".oC", "loading preferences from " + resourceName
					+ ".xml");

			Context context = mStateManager.getContext();

			// debug
			mDebug = getPreferenceManager().getSharedPreferences().getBoolean(
					"pref_dev_opts_debug", mDebug);
			if (mDebug) {
				Toast.makeText(getActivity(), "debug is enabled!",
						Toast.LENGTH_LONG).show();
			}

			final int resourceId = context.getResources().getIdentifier(
					resourceName, "xml", context.getPackageName());

			PreferenceManager
					.setDefaultValues(getActivity(), resourceId, false);
			addPreferencesFromResource(resourceId);
		} catch (Exception e) {
			Log_d(LOG_TAG + ".oC", "exception occurred! " + e.getMessage());
		}

		// setup about preference for debug
		Preference about = findPreference("pref_about");
		if (about != null) {
			// init onClick listener
			about.setEnabled(true);
			about.setSelectable(true);
			about.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					mAboutClicked += 1;
					if (mAboutClicked == mAboutClickCount) {
						mAboutClicked = 0;
						SharedPreferences prefs = getPreferenceManager()
								.getSharedPreferences();
						mDebug = !prefs
								.getBoolean("pref_dev_opts_debug", false); // toggle
																			// debug
						prefs.edit().putBoolean("pref_dev_opts_debug", mDebug)
								.commit();
						Toast.makeText(
								getActivity(),
								"debug is "
										+ (prefs.getBoolean(
												"pref_dev_opts_debug", false) ? "enabled"
												: "disabled") + " now!",
								Toast.LENGTH_LONG).show();
					}

					return true;
				}
			});

			// mask text as disabled
			about.setTitle(getTextDisabledFormatted(about.getTitle()));
			about.setSummary(getTextDisabledFormatted(about.getSummary()));
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		Log_d(LOG_TAG + ".oR", "resuming");

		SharedPreferences prefs = getPreferenceManager().getSharedPreferences();

		try {
			Activity act = getActivity();
			PackageInfo info = act.getPackageManager().getPackageInfo(
					act.getPackageName(), 0);

			Log.d(LOG_TAG + ".oR", "versionCode = " + info.versionCode);

			int old_version = prefs.getInt("version", 3);
			if (old_version < info.versionCode) {
				prefs.edit().putInt("version", info.versionCode).commit();

				Log.d(LOG_TAG + ".oR", "stored version code");
			}

			if (old_version < 5) {
				new AlertDialog.Builder(act)
						.setMessage(
								String.format(
										getResources().getString(
												R.string.firstrun_message),
										info.versionName))
						.setPositiveButton(R.string.firstrun_ok,
								new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog,
											int id) {
										// User clicked OK button
									}
								}).create().show();
			}
		} catch (NameNotFoundException e) {
			// this can't happen
		}

		prefs.registerOnSharedPreferenceChangeListener(this);

		prefs.edit()
				.putBoolean("pref_enabled",
						mStateManager.service_running(CoreService.class))
				.putBoolean(
						"pref_do_notifications",
						mStateManager
								.service_running(NotificationService.class))
				.commit();

		if (!mStateManager.getSystemApp()) {
			prefs.edit().putBoolean("pref_lockmode", true);
			findPreference("pref_lockmode").setEnabled(false);
		}

		if (!prefs.getBoolean("pref_runasroot", false)) {
			findPreference("pref_realhall").setEnabled(false);
			prefs.edit().putBoolean("pref_realhall", false);
		}
		// phone control
		enablePhoneScreen(prefs);
		updatePhoneControlTtsDelay(prefs);

	}

	@Override
	public void onPause() {
		super.onPause();
		Log_d(LOG_TAG + ".oP", "pausing");

	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log_d(LOG_TAG + ".oD", "destroying ");

		getPreferenceManager().getSharedPreferences()
				.unregisterOnSharedPreferenceChangeListener(this);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
		Log_d(LOG_TAG + ".oSPC", "changed key " + key);
		if (mStateManager.getDefaultActivityRunning()) {
			mStateManager.closeDefaultActivity();
		}

		// update display
		if (findPreference(key) instanceof CheckBoxPreference) {
			Log.d(LOG_TAG + ".oSPC", "toggling check box");
			((CheckBoxPreference) findPreference(key)).setChecked(prefs
					.getBoolean(key, false));
		} else if (findPreference(key) instanceof PreferenceSwitchable) {
			Log.d(LOG_TAG + ".oSPC", "toggling switch");
			((PreferenceSwitchable) findPreference(key)).setChecked(prefs
					.getBoolean(key, false));
		}

		// if the service is being enabled/disabled the key will be pref_enabled
		if (key.equals("pref_enabled")) {

			Log.d(LOG_TAG + ".oSPC",
					"pref_enabled is now " + prefs.getBoolean(key, false));

			if (prefs.getBoolean(key, false)) {
				if (prefs.getBoolean("pref_runasroot", false)) {
					AsyncSuAvailable localSuAvailable = new AsyncSuAvailable();
					localSuAvailable.execute();
				}
				mStateManager.startServices();
			} else {
				mStateManager.stopServices();
			}

			// if the default screen widget is being enabled/disabled the key
			// will be pref_default_widget
		} else if (key.equals("pref_default_widget")) {

			if (prefs.getBoolean(key, false)
					&& !mStateManager.hmAppWidgetManager
							.doesWidgetExist("default")) {
				mStateManager.register_widget("default");
			} else if (!prefs.getBoolean(key, false)
					&& mStateManager.hmAppWidgetManager
							.doesWidgetExist("default")) {
				mStateManager.unregister_widget("default");
			}

			// if the media screen widget is being enabled/disabled the key will
			// be pref_media_widget
		} else if (key.equals("pref_media_widget")) {

			if (prefs.getBoolean(key, false)
					&& !mStateManager.hmAppWidgetManager
							.doesWidgetExist("media")) {
				mStateManager.register_widget("media");
			} else if (!prefs.getBoolean(key, false)
					&& mStateManager.hmAppWidgetManager
							.doesWidgetExist("media")) {
				mStateManager.unregister_widget("media");
			}

		} else if (key.equals("pref_runasroot")) {

			AsyncSuAvailable localSuAvailable = new AsyncSuAvailable();
			localSuAvailable.execute();
		} else if (key.equals("pref_internalservice")) {
			mStateManager.stopServices(true);
			SystemClock.sleep(1000);
			mStateManager.startServices();
		} else if (key.equals("pref_realhall")) {
			if (prefs.getBoolean(key, false)) {
				if (!prefs.getBoolean("pref_runasroot", false)) {
					Toast.makeText(getActivity(),
							getString(R.string.pref_realhall_requirement),
							Toast.LENGTH_SHORT).show();
					prefs.edit().putBoolean(key, false).commit();
				} else if (prefs.getBoolean("pref_proximity", false)) {
					Toast.makeText(getActivity(),
							getString(R.string.pref_realhall_requirement),
							Toast.LENGTH_SHORT).show();
					prefs.edit().putBoolean(key, false).commit();
				} else {
					mStateManager.stopServices(true);
					SystemClock.sleep(1000);
					mStateManager.startServices();
				}
			}
		} else if (key.equals("pref_proximity")) {
			if (prefs.getBoolean(key, false)) {
				if (prefs.getBoolean("pref_realhall", false)) {
					Toast.makeText(getActivity(),
							getString(R.string.pref_proximity_requirement),
							Toast.LENGTH_SHORT).show();
					prefs.edit().putBoolean(key, false).commit();
				} else {
					mStateManager.stopServices(true);
					SystemClock.sleep(1000);
					mStateManager.startServices();
				}
			}
		} else if (key.equals("pref_do_notifications")) {
			doNotifications(getActivity(), prefs.getBoolean(key, false));
			// if the flash controls are being enabled/disabled the key will be
			// pref_widget
		} else if (key.equals("pref_flash_controls")) {

			if (prefs.getBoolean("pref_flash_controls_alternative", false)) {
				Toast.makeText(getActivity(),
						getString(R.string.alternative_torch_enabled),
						Toast.LENGTH_SHORT).show();
				prefs.edit().putBoolean(key, false).commit();
			} else if (prefs.getBoolean(key, false)) {
				try {
					PackageManager packageManager = getActivity()
							.getPackageManager();
					packageManager.getApplicationLogo("net.cactii.flash2");
				} catch (PackageManager.NameNotFoundException nfne) {
					// if the app isn't installed, just refuse to set the
					// preference
					Toast.makeText(getActivity(),
							getString(R.string.no_torch_app),
							Toast.LENGTH_SHORT).show();
					prefs.edit().putBoolean(key, false).commit();
				}
			}

			// if the flash controls are being enabled/disabled the key will be
			// pref_widget
		} else if (key.equals("pref_flash_controls_alternative")) {

			if (prefs.getBoolean("pref_flash_controls", false)) {
				Toast.makeText(getActivity(),
						getString(R.string.default_torch_enabled),
						Toast.LENGTH_SHORT).show();
				prefs.edit().putBoolean(key, false).commit();
			} else if (prefs.getBoolean(key, false)) {
				if (mStateManager.getDeviceHasFlash()) {
					prefs.edit().putBoolean(key, true).commit();
				} else {
					// if the device does not have camera flash feature refuse
					// to set the preference
					Toast.makeText(getActivity(), getString(R.string.no_torch),
							Toast.LENGTH_SHORT).show();
					prefs.edit().putBoolean(key, false).commit();
				}
			}

		} else if (key.equals("pref_realfullscreen")) {

			if (prefs.getBoolean("pref_disable_home", false)) {
				Toast.makeText(getActivity(),
						getString(R.string.pref_disable_enabled),
						Toast.LENGTH_SHORT).show();
				prefs.edit().putBoolean(key, false).commit();
			}
		} else if (key.equals("pref_disable_home")) {
			if (prefs.getBoolean("pref_realfullscreen", false)) {
				Toast.makeText(getActivity(),
						getString(R.string.pref_realfullscreen_enabled),
						Toast.LENGTH_SHORT).show();
				prefs.edit().putBoolean(key, false).commit();
			}

			// preferences_phone
		} else if (key.equals("pref_phone_controls_tts_delay")) {
			updatePhoneControlTtsDelay(prefs);

			// preferences_phone
		} else if (key.equals("pref_phone_controls_tts_delay")) {
			updatePhoneControlTtsDelay(prefs);

		}

		;

		// phone control
		enablePhoneScreen(prefs);

		((CoreApp) getActivity().getApplicationContext()).restart();

		if (key.equals("pref_force_restart")) {
			prefs.edit().putBoolean(key, false).commit();
			Intent mStartActivity = new Intent(getActivity()
					.getApplicationContext(), Configuration.class);
			int mPendingIntentId = 123456;
			PendingIntent mPendingIntent = PendingIntent.getActivity(
					getActivity().getApplicationContext(), mPendingIntentId,
					mStartActivity, PendingIntent.FLAG_CANCEL_CURRENT);
			AlarmManager mgr = (AlarmManager) getActivity()
					.getApplicationContext().getSystemService(
							Context.ALARM_SERVICE);
			mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 1000,
					mPendingIntent);
			System.exit(0);
		}
	}

	private void updatePhoneControlTtsDelay(SharedPreferences prefs) {
		Preference preference = findPreference("pref_phone_controls_tts_delay");

		if (preference != null && (preference instanceof ListPreference)) {
			preference.setSummary(((ListPreference) preference).getEntry());
		}
	}

	private void enablePhoneScreen(SharedPreferences prefs) {
		boolean phoneControlState = prefs.getBoolean("pref_enabled", false)
				&& prefs.getBoolean("pref_runasroot", false);
		boolean phoneControlConfig = prefs.getBoolean("pref_phone_controls",
				false);
		Preference phoneControl = findPreference("pref_phone_controls_user");

		if (phoneControlConfig != phoneControlState && phoneControl != null) {
			phoneControl.setEnabled(phoneControlState);
		}
		if (phoneControlConfig != (phoneControlState && prefs.getBoolean(
				"pref_phone_controls_user", false))) {
			prefs.edit().putBoolean("pref_phone_controls", !phoneControlConfig)
					.commit();
		}
	}

	private void Log_d(String tag, String message) {
		if (mDebug) {
			Log.d(tag, message);
		}
	}

	private SpannableString getTextDisabledFormatted(CharSequence text) {
		// TODO: read default text color
		int defaultTextColor = Color.BLACK;

		int alpha = Color.argb((int) (0.5f * 255), Color.red(defaultTextColor),
				Color.green(defaultTextColor), Color.blue(defaultTextColor));

		SpannableString spannableString = new SpannableString(text);
		spannableString.setSpan(new ForegroundColorSpan(alpha), 0,
				text.length(), 0);

		return spannableString;
	}

	private void doNotifications(Activity act, boolean enable) {

		if (enable && !mStateManager.getNotificationSettingsOngoing()
				&& !mStateManager.service_running(NotificationService.class)) {
			mStateManager.setNotificationSettingsOngoing(true);
			Toast.makeText(act, act.getString(R.string.notif_please_check),
					Toast.LENGTH_SHORT).show();
			act.startActivityForResult(new Intent(
					"android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
					CoreApp.NOTIFICATION_LISTENER_ON);
		} else if (!enable && !mStateManager.getNotificationSettingsOngoing()
				&& mStateManager.service_running(NotificationService.class)) {
			mStateManager.setNotificationSettingsOngoing(true);
			Toast.makeText(act, act.getString(R.string.notif_please_uncheck),
					Toast.LENGTH_SHORT).show();
			act.startActivityForResult(new Intent(
					"android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
					CoreApp.NOTIFICATION_LISTENER_OFF);
		}

	}

	@Override
	public boolean onPreferenceTreeClick(PreferenceScreen screen,
			Preference pref) {
		super.onPreferenceTreeClick(screen, pref);

		if (pref != null && pref instanceof PreferenceScreen) {
			// make the back button on the action bar work (why doesn't it work
			// by default???)
			// FIXME this is another hack
			// thanks be to
			// https://stackoverflow.com/questions/16374820/action-bar-home-button-not-functional-with-nested-preferencescreen

			final PreferenceScreen ps = (PreferenceScreen) pref;
			if (ps.getDialog() != null) {
				ps.getDialog().getActionBar().setDisplayHomeAsUpEnabled(true);

				// carefully walk up two levels from the home button
				View v = ps.getDialog().findViewById(android.R.id.home);
				if (v != null) {
					if (v.getParent() != null && v.getParent() instanceof View) {
						v = (View) v.getParent();
						if (v.getParent() != null
								&& v.getParent() instanceof View) {
							v = (View) v.getParent();

							// found the view we want, make it so
							v.setOnClickListener(new OnClickListener() {

								@Override
								public void onClick(View view) {
									ps.getDialog().dismiss();
								}

							});
						}
					}
				}
			}
		}

		return false;
	}

	private class AsyncSuAvailable extends AsyncTask<Boolean, Boolean, Boolean> {
		@Override
		protected Boolean doInBackground(Boolean... params) {
			return Shell.SU.available();
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (!result) {
				Toast.makeText(
						getActivity(),
						"Root access not granted - cannot enable root features!",
						Toast.LENGTH_SHORT).show();
				getPreferenceManager().getSharedPreferences().edit()
						.putBoolean("pref_runasroot", false).commit();
			}
		}
	}
}
