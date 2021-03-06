package org.handmadeideas.chordreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Typeface;
import android.os.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.*;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import android.widget.TextView.OnEditorActionListener;
import org.handmadeideas.chordreader.adapter.FileAdapter;
import org.handmadeideas.chordreader.chords.Chord;
import org.handmadeideas.chordreader.chords.NoteNaming;
import org.handmadeideas.chordreader.chords.regex.ChordInText;
import org.handmadeideas.chordreader.chords.regex.ChordParser;
import org.handmadeideas.chordreader.data.ColorScheme;
import org.handmadeideas.chordreader.db.ChordReaderDBHelper;
import org.handmadeideas.chordreader.db.Transposition;
import org.handmadeideas.chordreader.helper.*;
import org.handmadeideas.chordreader.util.InternalURLSpan;
import org.handmadeideas.chordreader.util.Pair;
import org.handmadeideas.chordreader.util.StringUtil;
import org.handmadeideas.chordreader.util.UtilLogger;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FindChordsActivity extends Activity implements OnEditorActionListener, OnClickListener, TextWatcher, OnTouchListener {

	private static final int CHORD_POPUP_Y_OFFSET_IN_SP = 24;
	private static final int PROGRESS_DIALOG_MIN_TIME = 600;
	private static final long HISTORY_WINDOW = TimeUnit.SECONDS.toMillis(60 * 60 * 24 * 360); // about one year 
	private static final long PAGE_WAIT_TIME = 3000;
	
	private static UtilLogger log = new UtilLogger(FindChordsActivity.class);
	
	private static float lastXCoordinate, lastYCoordinate;
	
	private AutoCompleteTextView searchEditText;
	private WebView webView;
	private View messageSecondaryView, searchingView;
	private TextView messageTextView;
	private ProgressBar progressBar;
	private ImageView infoIconImageView;
	private Button searchButton;
	private PowerManager.WakeLock wakeLock;
	
	private CustomWebViewClient client = new CustomWebViewClient();
	
	private Handler handler = new Handler(Looper.getMainLooper());
	
	private ChordWebpage chordWebpage;
	private String html = null;
	private String url = null;
	private String searchEngineURL = null;

	private String filename;
	private volatile String chordText;
	private List<ChordInText> chordsInText;
	private int capoFret = 0;
	private int transposeHalfSteps = 0;

	private boolean isEditedTextToSave = false;

	private TextView viewingTextView;

	private AutoScrollView viewingScrollView;
	private LinearLayout mainView;
	private LinearLayout chordsViewingLayout;

	private ImageButton autoscrollPlayButton, autoscrollPauseButton, autoscrollSlowerButton, autoscrollFasterButton;

	private GestureDetector mDetector;
	private Handler metronomHandler = new Handler();
	private Timer metronomTimer;

	private ArrayAdapter<String> queryAdapter;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        
        setContentView(R.layout.find_chords);
        
        setUpWidgets();
        
		PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager != null ? powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, getPackageName()) : null;
        
        // initially, search rather than view chords
        switchToSearchingMode();
        
        initializeChordDictionary();
        
        applyColorScheme();

        applySearchEngineURL();

        showInitialMessage();
    }
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    }
    

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
	    MenuInflater inflater = getMenuInflater();
	    inflater.inflate(R.menu.main_menu, menu);
	    
	    return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		
	    switch (item.getItemId()) {
	    case R.id.menu_about:
	    	startAboutActivity();
	    	break;
	    case R.id.menu_manage_files:
	    	startDeleteSavedFilesDialog();
	    	break;
	    case R.id.menu_search_chords:
	    	switchToSearchingMode();
	    	break;
	    case R.id.menu_new_file:
			showNewFileDialog();
			break;
	    case R.id.menu_open_file:
	    	showOpenFileDialog(true);
	    	break;
	    case R.id.menu_save_chords:
	    	showSaveChordChartDialog("");
	    	break;
	    case R.id.menu_transpose:
	    	createTransposeDialog();
	    	break;
	    case R.id.menu_stop:
	    	stopWebView();
	    	break;
	    case R.id.menu_refresh:
	    	refreshWebView();
	    	break;
	    case R.id.menu_settings:
	    	startSettingsActivity();
	    	break;
	    case R.id.menu_edit_file:
	    	showConfirmChordChartDialog(true);
	    	break;
	    	
	    }
	    return false;
	}

	private void startSettingsActivity() {

		startActivityForResult(new Intent(this, SettingsActivity.class), 1);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		
		// came back from the settings activity; need to update the text size
		PreferenceHelper.clearCache();
		viewingTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, PreferenceHelper.getTextSizePreference(this));
		
		// reapply color scheme
		applyColorScheme();
		
		// if the note naming changed, then update the currently displayed file
		if (data != null 
				&& data.hasExtra(SettingsActivity.EXTRA_NOTE_NAMING_CHANGED)
				&& data.getBooleanExtra(SettingsActivity.EXTRA_NOTE_NAMING_CHANGED, false)
				&& isInViewingMode()) {
			if (isEditedTextToSave)
				switchToViewingMode();
			else
				openFile(filename);
		}

		// reapply search engine
		applySearchEngineURL();
	}

	private void startAboutActivity() {
		Intent intent = new Intent(this, AboutActivity.class);
		
		startActivity(intent);
		
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		
		MenuItem searchChordsMenuItem = menu.findItem(R.id.menu_search_chords);
		
		boolean searchMode = searchingView.getVisibility() == View.VISIBLE;
		
		// if we're already in search mode, no need to show this menu item
		searchChordsMenuItem.setVisible(!searchMode);
		searchChordsMenuItem.setEnabled(!searchMode);
		
		// if we're not in viewing mode, there's no need to show the 'save chords' or 'edit chords' menu item
		
		MenuItem saveChordsMenuItem = menu.findItem(R.id.menu_save_chords);
		
		saveChordsMenuItem.setVisible(!searchMode);
		saveChordsMenuItem.setEnabled(!searchMode);
		
		MenuItem editMenuItem = menu.findItem(R.id.menu_edit_file);
		
		editMenuItem.setVisible(!searchMode);
		editMenuItem.setEnabled(!searchMode);
		
		// only show transpose in viewing mode
		
		MenuItem transposeMenuItem = menu.findItem(R.id.menu_transpose);
		
		transposeMenuItem.setVisible(!searchMode);
		transposeMenuItem.setEnabled(!searchMode);
		
		// stop and refresh only apply to searching mode
		
		MenuItem stopMenuItem = menu.findItem(R.id.menu_stop);
		MenuItem refreshMenuItem = menu.findItem(R.id.menu_refresh);
		
		boolean webViewVisible = webView.getVisibility() == View.VISIBLE;
		boolean pageLoading = progressBar.getVisibility() == View.VISIBLE; // page still loading
			
		stopMenuItem.setEnabled(searchMode && pageLoading);
		stopMenuItem.setVisible(searchMode && pageLoading);
		
		refreshMenuItem.setEnabled(searchMode && webViewVisible && !pageLoading);
		refreshMenuItem.setVisible(searchMode && webViewVisible && !pageLoading);
		
		return super.onPrepareOptionsMenu(menu);
	}



	private void setUpWidgets() {

		searchEditText = (AutoCompleteTextView) findViewById(R.id.find_chords_edit_text);
		searchEditText.setOnEditorActionListener(this);
		searchEditText.addTextChangedListener(this);
		searchEditText.setOnClickListener(this);


		long queryLimit = System.currentTimeMillis() - HISTORY_WINDOW;
		ChordReaderDBHelper dbHelper = null;
		try {
			dbHelper = new ChordReaderDBHelper(this);
			List<String> queries = dbHelper.findAllQueries(queryLimit, "");
			queryAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, queries);
			searchEditText.setAdapter(queryAdapter);
		} finally {
			if (dbHelper != null) {
				dbHelper.close();
			}
		}

		webView = (WebView) findViewById(R.id.find_chords_web_view);
		webView.setWebViewClient(client);
		//webView.getSettings().setUserAgentString(DESKTOP_USERAGENT);

		/* JavaScript must be enabled if you want it to work, obviously */
		webView.getSettings().setJavaScriptEnabled(true);

		/* Register a new JavaScript interface called HTMLOUT */
		webView.addJavascriptInterface(this, "HTMLOUT");

		progressBar = (ProgressBar) findViewById(R.id.find_chords_progress_bar);
		infoIconImageView = (ImageView) findViewById(R.id.find_chords_image_view);
		searchButton = (Button) findViewById(R.id.find_chords_search_button);
		searchButton.setOnClickListener(this);

		messageSecondaryView = findViewById(R.id.find_chords_message_secondary_view);
		messageSecondaryView.setOnClickListener(this);
		messageSecondaryView.setEnabled(false);

		messageTextView = (TextView) findViewById(R.id.find_chords_message_text_view);

		viewingTextView = (TextView) findViewById(R.id.find_chords_viewing_text_view);
		viewingTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, PreferenceHelper.getTextSizePreference(this));
		viewingScrollView = (AutoScrollView) findViewById(R.id.find_chords_viewing_scroll_view);
		viewingScrollView.setTargetTextView(viewingTextView);

		chordsViewingLayout = (LinearLayout) findViewById(R.id.chords_viewing_layout);
		chordsViewingLayout.setVisibility(View.GONE);

		autoscrollPlayButton = (ImageButton) findViewById(R.id.autoScrollPlayButton);
		autoscrollPlayButton.setVisibility(View.GONE);
		autoscrollPlayButton.setOnClickListener(this);
		autoscrollPauseButton = (ImageButton) findViewById(R.id.autoScrollPauseButton);
		autoscrollPauseButton.setVisibility(View.GONE);
		autoscrollPauseButton.setOnClickListener(this);
		autoscrollSlowerButton = (ImageButton) findViewById(R.id.autoScrollSlower);
		autoscrollSlowerButton.setVisibility(View.GONE);
		autoscrollSlowerButton.setOnClickListener(this);
		autoscrollFasterButton = (ImageButton) findViewById(R.id.autoScrollFaster);
		autoscrollFasterButton.setVisibility(View.GONE);
		autoscrollFasterButton.setOnClickListener(this);

		searchingView = findViewById(R.id.find_chords_finding_view);
		mainView = (LinearLayout) findViewById(R.id.find_chords_main_view);

		mDetector = new GestureDetector(this, new MyGestureListener());
		// pass the events to the gesture detector
		// a return value of true means the detector is handling it
		// a return value of false means the detector didn't
		// recognize the event
		OnTouchListener touchListener = new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {

				final int action = event.getAction();

				if (action == MotionEvent.ACTION_UP) {
					if (viewingScrollView.isAutoScrollOn()) {
						viewingScrollView.postDelayed(new Runnable() { //wait a moment to check if a fling was caused
							@Override
							public void run() {
								if (!viewingScrollView.isFlingActive() && viewingScrollView.isAutoScrollOn() && !viewingScrollView.isAutoScrollActive()) {
									viewingScrollView.startAutoScroll();
								}
							}
						}, 100);
					}
				}



				return mDetector.onTouchEvent(event);

			}
		};

//		viewingTextView.setOnTouchListener(this);
//		viewingScrollView.setOnTouchListener(touchListener);
		viewingTextView.setOnTouchListener(touchListener);

		viewingScrollView.setOnFlingListener(new AutoScrollView.OnFlingListener() {
			@Override
			public void onFlingStarted() {
				viewingScrollView.setFlingActive(true);
			}

			@Override
			public void onFlingStopped() {
				viewingScrollView.setFlingActive(false);
				if (viewingScrollView.isAutoScrollOn())
					viewingScrollView.startAutoScroll();
			}
		});
	}

	@Override
	protected void onPause() {
		super.onPause();
		
		if (wakeLock.isHeld()) {
			log.d("Releasing wakelock");
			wakeLock.release();
		}
		
	}


	@Override
	protected void onResume() {
		super.onResume();
		
		// just in case the text size has changed
		viewingTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, PreferenceHelper.getTextSizePreference(this));
		
		if (isInViewingMode() && !wakeLock.isHeld()) {
			log.d("Acquiring wakelock");
			wakeLock.acquire(10*60*1000L /*10 minutes*/);
		}
		
	}

	@Override
	public void onBackPressed() {
		if (isEditedTextToSave) {
			showSavePromptDialog("onBackPressed");
		}
		else {
			if (webView.copyBackForwardList().getCurrentIndex() > 0) {
				webView.goBack();
			}
			else {

				new AlertDialog.Builder(this)
						.setIcon(android.R.drawable.ic_dialog_alert)
						.setTitle(R.string.closing_chordreader)
						.setMessage(R.string.closing_chordreader_message)
						.setNegativeButton(R.string.no, null)
						.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								finish();
							}

						})
						.show();
			}
		}

	}

	private void showSavePromptDialog(final String callingMethod) {
		new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.unsaved_changes)
				.setMessage(R.string.unsaved_changes_message)
				.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {

						isEditedTextToSave = false;
						proceedAfterSaving(callingMethod);

					}
				})
				.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						showSaveChordChartDialog(callingMethod);
					}
				})
				.show();
	}

	private NoteNaming getNoteNaming() {
		return PreferenceHelper.getNoteNaming(this);
	}
	
	private void refreshWebView() {
		webView.reload();
		
	}

	private void stopWebView() {
		
		Toast.makeText(this, R.string.stopping, Toast.LENGTH_SHORT).show();
		webView.stopLoading();
		
	}
	
	private boolean isInViewingMode() {
		return viewingScrollView.getVisibility() == View.VISIBLE;
	}
	
	private void createTransposeDialog() {
		
		final View view = DialogHelper.createTransposeDialogView(this, capoFret, transposeHalfSteps);
		new AlertDialog.Builder(this)
			.setTitle(R.string.transpose)
			.setCancelable(true)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				
				@Override
				public void onClick(DialogInterface dialog, int which) {
					
					// grab the user's chosen values for the capo and the transposition

					View transposeView = view.findViewById(R.id.transpose_include);
					View capoView = view.findViewById(R.id.capo_include);
					
					int newTransposeHalfSteps = DialogHelper.getSeekBarValue(transposeView) + DialogHelper.TRANSPOSE_MIN;
					int newCapoFret = DialogHelper.getSeekBarValue(capoView) + DialogHelper.CAPO_MIN;
					
					log.d("transposeHalfSteps is now %d", newTransposeHalfSteps);
					log.d("capoFret is now %d", newCapoFret);
					
					changeTransposeOrCapo(newTransposeHalfSteps, newCapoFret);
					
					dialog.dismiss();
					
				}
			})
			.setView(view)
			.show();
		
	}
	
	protected void changeTransposeOrCapo(final int newTransposeHalfSteps, final int newCapoFret) {
		
		// persist
		if (filename != null) {
			ChordReaderDBHelper dbHelper = new ChordReaderDBHelper(this);
			dbHelper.saveTransposition(filename, newTransposeHalfSteps, newCapoFret);
			dbHelper.close();
		}
		
		final ProgressDialog progressDialog = new ProgressDialog(this);
		progressDialog.setTitle(R.string.transposing);
		progressDialog.setMessage(getText(R.string.please_wait));
		progressDialog.setIndeterminate(true);
		
		// transpose in background to avoid jankiness
		AsyncTask<Void,Void,Spannable> task = new AsyncTask<Void, Void, Spannable>(){
			
			
			
			@Override
			protected void onPreExecute() {
				super.onPreExecute();
				progressDialog.show();
			}

			@Override
			protected Spannable doInBackground(Void... params) {
				
				long start = System.currentTimeMillis();
				
				
				int capoDiff = capoFret - newCapoFret;
				int transposeDiff = transposeHalfSteps - newTransposeHalfSteps;
				capoFret = newCapoFret;
				transposeHalfSteps = newTransposeHalfSteps;
				
				updateChordsInTextForTransposition(transposeDiff, capoDiff);
				
				Spannable chordTextSpannable = buildUpChordTextToDisplay();

				long elapsed = System.currentTimeMillis() - start;
				
				if (elapsed < PROGRESS_DIALOG_MIN_TIME) {
					// show progressdialog for at least 1 second, or else it goes by too fast
					// XXX: this is a weird UI hack, but I don't know what else to do
					try {
						Thread.sleep(PROGRESS_DIALOG_MIN_TIME - elapsed);
					} catch (InterruptedException e) {
						log.e(e,"unexpected exception");
					}
				}
				
				
				return chordTextSpannable;
				
			}

			@Override
			protected void onPostExecute(Spannable chordText) {
				super.onPostExecute(chordText);
				
				applyLinkifiedChordsTextToTextView(chordText);
				progressDialog.dismiss();
			}
			
			
		};
		
		task.execute((Void)null);

		
	}

	private void updateChordsInTextForTransposition(int transposeDiff, int capoDiff) {
		
		for (ChordInText chordInText : chordsInText) {
			
			chordInText.setChord(TransposeHelper.transposeChord(
					chordInText.getChord(), capoDiff, transposeDiff));
		}
		
	}

	private void startDeleteSavedFilesDialog() {

    	if (isEditedTextToSave) {
			showSavePromptDialog("startDeleteSavedFilesDialog");
		}
		else {
			if (!checkSdCard()) {
				return;
			}

			List<CharSequence> filenames = new ArrayList<CharSequence>(SaveFileHelper.getSavedFilenames());

			if (filenames.isEmpty()) {
				Toast.makeText(this, R.string.no_saved_files, Toast.LENGTH_SHORT).show();
				return;
			}

			final CharSequence[] filenameArray = filenames.toArray(new CharSequence[filenames.size()]);

			final FileAdapter dropdownAdapter = new FileAdapter(
					this, filenames, -1, true);

			final TextView messageTextView = new TextView(this);
			messageTextView.setText(R.string.select_files_to_delete);
			messageTextView.setPadding(3, 3, 3, 3);

			AlertDialog.Builder builder = new AlertDialog.Builder(this);

			builder.setTitle(R.string.manage_saved_files)
					.setCancelable(true)
					.setNegativeButton(android.R.string.cancel, null)
					.setNeutralButton(R.string.delete_all, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							boolean[] allChecked = new boolean[dropdownAdapter.getCount()];

							for (int i = 0; i < allChecked.length; i++) {
								allChecked[i] = true;
							}
							verifyDelete(filenameArray, allChecked, dialog);

						}
					})
					.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {

							verifyDelete(filenameArray, dropdownAdapter.getCheckedItems(), dialog);

						}
					})
					.setView(messageTextView)
					.setSingleChoiceItems(dropdownAdapter, 0, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							dropdownAdapter.checkOrUncheck(which);

						}
					});

			builder.show();
		}
	}

	protected void verifyDelete(final CharSequence[] filenameArray,
			final boolean[] checkedItems, final DialogInterface parentDialog) {
		
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		
		int deleteCount = 0;
		
		for (int i = 0; i < checkedItems.length; i++) {
			if (checkedItems[i]) {
				deleteCount++;
			}
		}
		
		
		final int finalDeleteCount = deleteCount;
		
		if (finalDeleteCount > 0) {
			
			builder.setTitle(R.string.delete_saved_file)
				.setCancelable(true)
				.setMessage(String.format(getText(R.string.are_you_sure).toString(), finalDeleteCount))
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				
				@Override
				public void onClick(DialogInterface dialog, int which) {
					// ok, delete
					
					for (int i = 0; i < checkedItems.length; i++) {
						if (checkedItems[i]) {
							SaveFileHelper.deleteFile(filenameArray[i].toString());
						}
					}
					
					String toastText = String.format(getText(R.string.files_deleted).toString(), finalDeleteCount);
					Toast.makeText(FindChordsActivity.this, toastText, Toast.LENGTH_SHORT).show();
					
					dialog.dismiss();
					parentDialog.dismiss();
					
				}
			});
			builder.setNegativeButton(android.R.string.cancel, null);
			builder.show();
		}
	}
	
	
	private void showOpenFileDialog(final boolean sortedByDate) {

		if (isEditedTextToSave) {
			showSavePromptDialog("showOpenFileDialog");
		}
		else {
			if (!checkSdCard()) {
				return;
			}

			final List<CharSequence> filenames = new ArrayList<CharSequence>(SaveFileHelper.getSavedFilenames());

			if (filenames.isEmpty()) {
				Toast.makeText(this, R.string.no_saved_files, Toast.LENGTH_SHORT).show();
				return;
			}
			if (!sortedByDate) {

				Collections.sort(filenames, new Comparator<CharSequence>() {

					@Override
					public int compare(CharSequence first, CharSequence second) {
						return first.toString().toLowerCase().compareTo(second.toString().toLowerCase());
					}
				});
			}

			int fileToSelect = filename != null ? filenames.indexOf(filename) : -1;

			ArrayAdapter<CharSequence> dropdownAdapter = new FileAdapter(this, filenames, fileToSelect, false);

			AlertDialog.Builder builder = new AlertDialog.Builder(this);

			builder.setTitle(R.string.open_file)
					.setCancelable(true)
					.setPositiveButton(sortedByDate ? R.string.sort_az : R.string.sort_by_date, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							showOpenFileDialog(!sortedByDate); // switch sorting
						}
					})
					.setNegativeButton(android.R.string.cancel, null)
					.setSingleChoiceItems(dropdownAdapter, fileToSelect, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							String filename = filenames.get(which).toString();
							viewingScrollView.resetAutoScrollView();
							animationSwitch(autoscrollPauseButton, autoscrollPlayButton);
							openFile(filename);

						}
					});

			builder.show();
		}
	}	
	
    private void showInitialMessage() {

		boolean isFirstRun = PreferenceHelper.getFirstRunPreference(getApplicationContext());
		if (isFirstRun) {
			
			View view = View.inflate(this, R.layout.intro_dialog, null);
			TextView textView = (TextView) view.findViewById(R.id.first_run_text_view);
			textView.setMovementMethod(LinkMovementMethod.getInstance());
			textView.setText(R.string.first_run_message);
			textView.setLinkTextColor(ColorStateList.valueOf(getResources().getColor(R.color.linkColorBlue)));
			new AlertDialog.Builder(this)
					.setTitle(R.string.first_run_title)
			        .setView(view)
			        .setPositiveButton(android.R.string.ok,
						new DialogInterface.OnClickListener() {

							public void onClick(DialogInterface dialog, int which) {
								PreferenceHelper.setFirstRunPreference(getApplicationContext(), false);
							}
						})
					.setCancelable(false)
			        .setIcon(R.drawable.chord_reader_icon).show();

		}


	}

	private void openFile(String filenameToOpen) {

		filename = filenameToOpen;
		chordText = SaveFileHelper.openFile(filename);
		if (chordText.isEmpty())
			switchToSearchingMode();
		else
			switchToViewingMode();
	}


	public void showHTML(String html) {


    	log.d("html is %s...", html != null ? (html.substring(0, Math.min(html.length(),30))) : html);

		this.html = html;

		handler.post(new Runnable() {

			@Override
			public void run() {
				urlAndHtmlLoaded();

			}
		});

     }


	@Override
	public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {

		if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
			performSearch();
			return true;
		}


		return false;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)  {

	    if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
	    	if (handleBackButton()) {
	    		return true;
	    	}
	    } else if (keyCode == KeyEvent.KEYCODE_SEARCH && event.getRepeatCount() == 0) {

	    	InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
	    	searchEditText.requestFocus();

	    	// show keyboard

			imm.showSoftInput(searchEditText, 0);

	    	return true;


	    }

	    return super.onKeyDown(keyCode, event);
	}

	private boolean handleBackButton() {

		if (isInViewingMode()) {

			// TODO

		} else { // searching mode

			if (webView.canGoBack()) {
	    		webView.goBack();
	    		return true;
			}
		}
		return false;

	}

	private void initializeChordDictionary() {
		// do in the background to avoid jank
		new AsyncTask<Void, Void, Void>(){

			@Override
			protected Void doInBackground(Void... params) {
				ChordDictionary.initialize(FindChordsActivity.this);
				return null;
			}
		}.execute((Void)null);

	}

	private void performSearch() {

		// dismiss soft keyboard
		InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
		imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);

		String searchText = (searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim());

		if (TextUtils.isEmpty(searchText)) {
			return;
		}

		// save the query, add it to the auto suggest text view
		saveQuery(searchText.toString());


		searchText = searchText + " " + getText(R.string.chords_keyword);

		String urlEncoded = null;
		try {
			urlEncoded = URLEncoder.encode(searchText.toString(), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			log.e(e, "this should never happen");
		}

		loadUrl(searchEngineURL + urlEncoded);

	}

	private void saveQuery(String searchText) {

		log.d("saving: '%s'", searchText);

		ChordReaderDBHelper dbHelper = null;

		try {
			dbHelper = new ChordReaderDBHelper(this);
			boolean newQuerySaved = dbHelper.saveQuery(searchText);

			// don't add duplicates
			if (newQuerySaved) {
				queryAdapter.insert(searchText, 0); // add first so it shows up first
			}
		} finally {
			if (dbHelper != null) {
				dbHelper.close();
			}
		}

	}

	private void loadUrl(String url) {

		log.d("url is: %s", url);

		webView.loadUrl(url);

	}


	private void getHtmlFromWebView() {
        webView.loadUrl("" +
         		"javascript:window.HTMLOUT.showHTML(" +
         		"'<head>'+document.getElementsByTagName('html')[0].innerHTML+'</head>');");

	}

	public void setSearchEngineURL(String searchEngineURL) {
    	this.searchEngineURL = searchEngineURL;
	}

	public void urlLoading(String url) {
		progressBar.setVisibility(View.VISIBLE);
		infoIconImageView.setVisibility(View.GONE);
		messageTextView.setText(R.string.loading);
		messageSecondaryView.setEnabled(false);

	}

	public void urlLoaded(String url) {

		this.url = url;
		this.chordWebpage = findKnownWebpage(url);

		handler.post(new Runnable() {

			@Override
			public void run() {
				getHtmlFromWebView();

			}
		});

	}

	private void urlAndHtmlLoaded() {

		progressBar.setVisibility(View.GONE);
		infoIconImageView.setVisibility(View.VISIBLE);
		webView.setVisibility(View.VISIBLE);

		log.d("chordWebpage is: %s", chordWebpage);


		if ((chordWebpage != null && checkHtmlOfKnownWebpage())
				|| chordWebpage == null && checkHtmlOfUnknownWebpage()) {
			messageTextView.setText(R.string.chords_found);
			messageSecondaryView.setEnabled(true);

		} else {
			messageTextView.setText(R.string.find_chords_second_message);
			messageSecondaryView.setEnabled(true);
		}
	}

	private boolean checkHtmlOfUnknownWebpage() {

		if (url.contains(searchEngineURL)) {
			return false; // skip page - we're on the search results page
		}

		String txt = WebPageExtractionHelper.convertHtmlToText(html);
		return ChordParser.containsLineWithChords(txt, getNoteNaming());

	}

	private boolean checkHtmlOfKnownWebpage() {

		// check to make sure that, if this is a page from a known website, we can
		// be sure that there are chords on this page

		String chordChart = WebPageExtractionHelper.extractChordChart(
				chordWebpage, html, getNoteNaming());

		log.d("chordChart is %s...", chordChart != null ? (chordChart.substring(0, Math.min(chordChart.length(),30))) : chordChart);

		boolean result = ChordParser.containsLineWithChords(chordChart, getNoteNaming());

		log.d("checkHtmlOfKnownWebpage is: %s", result);

		return result;

	}

	private ChordWebpage findKnownWebpage(String url) {

		if (url.contains("chordie.com")) {
			return ChordWebpage.Chordie;
		}

		return null;
	}


	@Override
	public void onClick(View view) {
		switch (view.getId()) {
			case R.id.find_chords_search_button:
				performSearch();
				break;
			case R.id.find_chords_message_secondary_view:
				analyzeHtml();
				break;
			case R.id.find_chords_edit_text:
				// I think it's intuitive to select the whole text when you click here
				if (!TextUtils.isEmpty(searchEditText.getText())) {
					searchEditText.setSelection(0, searchEditText.getText().length());
				}
				break;
			case R.id.autoScrollPlayButton:
				startAutoscroll();
				break;
			case R.id.autoScrollPauseButton:
				stopAutoscroll();
				break;
			case R.id.autoScrollSlower:
				animationBlink(autoscrollSlowerButton);
				changeAutoScrollFactor(false);
				break;
			case R.id.autoScrollFaster:
				animationBlink(autoscrollFasterButton);
				changeAutoScrollFactor(true);
				break;
		}

	}

	private void analyzeHtml() {
//removed section for known webpage as html structure may change -> chordie pattern doesn't work anymore
/*		if (chordWebpage != null) {
			// known webpage

			log.d("known web page: %s", chordWebpage);

			chordText = WebPageExtractionHelper.extractChordChart(
					chordWebpage, html, getNoteNaming());
		} else {

 */
			// unknown webpage

			log.d("unknown webpage");

			chordText = WebPageExtractionHelper.extractLikelyChordChart(html, getNoteNaming());


			if (chordText == null) { // didn't find a good extraction, so use the entire html

				log.d("didn't find a good chord chart using the <pre> tag");

				chordText = WebPageExtractionHelper.convertHtmlToText(html);
			}
//		}

		showConfirmChordChartDialog(false);

	}

	private void showConfirmChordChartDialog(boolean editMode) {

		LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

		final EditText editText = (EditText) inflater.inflate(R.layout.confirm_chords_edit_text, null);
		editText.setText(chordText);
		editText.setTypeface(Typeface.MONOSPACE);
		

		AlertDialog.Builder builder = new AlertDialog.Builder(this);

		builder.setTitle(editMode? R.string.edit_chords : R.string.confirm_chordchart)
				.setTitle(editMode? R.string.edit_chords : R.string.confirm_chordchart)
				.setView(editText)
				.setCancelable(true)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						chordText = editText.getText().toString();
						switchToViewingMode();
						isEditedTextToSave = true;
					}
				});

		AlertDialog alertDialog = builder.create();
		alertDialog.show();
		WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
		lp.copyFrom(alertDialog.getWindow().getAttributes());
		lp.width = WindowManager.LayoutParams.MATCH_PARENT;
		lp.height = WindowManager.LayoutParams.MATCH_PARENT;
		alertDialog.getWindow().setAttributes(lp);

		//log.d(chordText);
		
	}

	// This function shows a filename suggesting dialog and creates a new file with its output.
	protected void showNewFileDialog() {

		if (isEditedTextToSave) {
			showSavePromptDialog("showNewFileDialog");
		} else {
			if (!checkSdCard()) {
				return;
			}

			final EditText editText = createEditTextForFilenameSuggestingDialog(); // TODO This might suggest the wrong filename sometimes


			DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {

				@Override
				public void onClick(DialogInterface dialog, int which) {

					if (isInvalidFilename(editText.getText())) {
						Toast.makeText(FindChordsActivity.this, R.string.enter_good_filename, Toast.LENGTH_SHORT).show();
					} else {

						if (SaveFileHelper.fileExists(editText.getText().toString())) {

							new AlertDialog.Builder(FindChordsActivity.this)
									.setCancelable(true)
									.setTitle(R.string.overwrite_file_title)
									.setMessage(R.string.overwrite_file)
									.setNegativeButton(android.R.string.cancel, null)
									.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

										@Override
										public void onClick(DialogInterface dialog, int which) {
											saveFile(editText.getText().toString(), "");

										}
									})
									.show();

						} else {
							saveFile(editText.getText().toString(), "");
						}

						// Open the new file for editing
						openFile(editText.getText().toString());
						showConfirmChordChartDialog(true);
					}

					dialog.dismiss();
				}

			};

			showFilenameSuggestingDialog(editText, onClickListener, R.string.new_file);
		}
	}

	protected void showSaveChordChartDialog(final String callingMethod) {
		
		if (!checkSdCard()) {
			return;
		}
		
		final EditText editText = createEditTextForFilenameSuggestingDialog();
		
		DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {

				
				if (isInvalidFilename(editText.getText())) {
					Toast.makeText(FindChordsActivity.this, R.string.enter_good_filename, Toast.LENGTH_SHORT).show();
				} else {
					
					if (SaveFileHelper.fileExists(editText.getText().toString())) {

						new AlertDialog.Builder(FindChordsActivity.this)
							.setCancelable(true)
							.setTitle(R.string.overwrite_file_title)
							.setMessage(R.string.overwrite_file)
							.setNegativeButton(android.R.string.cancel, null)
							.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
								
								@Override
								public void onClick(DialogInterface dialog, int which) {
									saveFile(editText.getText().toString(), chordText);
									isEditedTextToSave = false;
									proceedAfterSaving(callingMethod);
								}
							})
							.show();
						
						
							
					} else {
						saveFile(editText.getText().toString(), chordText);
						isEditedTextToSave = false;
						proceedAfterSaving(callingMethod);
					}

					
				}
				dialog.dismiss();

			}
		};
		
		showFilenameSuggestingDialog(editText, onClickListener, R.string.save_file);		
		
	}

	private void proceedAfterSaving(String callingMethod) {
		if (callingMethod.equals("showOpenFileDialog"))
			showOpenFileDialog(true);
		else if (callingMethod.equals("switchToSearchingMode"))
			switchToSearchingMode();
		else if (callingMethod.equals("onBackPressed"))
			finish();
		else if (callingMethod.equals("showNewFileDialog"))
			showNewFileDialog();
		else if (callingMethod.equals("startDeleteSavedFilesDialog"))
			startDeleteSavedFilesDialog();
	}
	
	private boolean isInvalidFilename(CharSequence filename) {
		
		String filenameAsString = null;
		
		return TextUtils.isEmpty(filename)
				|| (filenameAsString = filename.toString()).contains("/")
				|| filenameAsString.contains(":")
				|| filenameAsString.contains(" ")
				|| !filenameAsString.endsWith(".txt");
				
	}	

	private void saveFile(final String filename, final String filetext) {
		
		// do in background to avoid jankiness
		
		AsyncTask<Void,Void,Boolean> saveTask = new AsyncTask<Void, Void, Boolean>(){

			@Override
			protected Boolean doInBackground(Void... params) {
				
				ChordReaderDBHelper dbHelper = new ChordReaderDBHelper(FindChordsActivity.this);
				dbHelper.saveTransposition(filename, transposeHalfSteps, capoFret);
				dbHelper.close();
				
				return SaveFileHelper.saveFile(filetext, filename);
				
			}

			@Override
			protected void onPostExecute(Boolean successfullySavedLog) {
				
				super.onPostExecute(successfullySavedLog);
				
				if (successfullySavedLog) {
					Toast.makeText(getApplicationContext(), R.string.file_saved, Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(getApplicationContext(), R.string.unable_to_save_file, Toast.LENGTH_LONG).show();
				}
			}
			
			
		};
		
		saveTask.execute((Void)null);
		
	}	
	private EditText createEditTextForFilenameSuggestingDialog() {
		
		final EditText editText = new EditText(this);
		editText.setSingleLine();
		editText.setSingleLine(true);
		editText.setInputType(InputType.TYPE_TEXT_VARIATION_FILTER);
		editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
		editText.setOnEditorActionListener(new OnEditorActionListener() {
			
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				
				if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
					// dismiss soft keyboard
					InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
					imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
					return true;
				}
				
				
				return false;
			}
		});
		
		String newFilename;
		
		if (filename != null) {
			//just suggest the same filename as before
			newFilename = filename;
		} else {
			// create an initial filename to suggest to the user
			if (!TextUtils.isEmpty(searchEditText.getText())) {
				newFilename = searchEditText.getText().toString().trim().replace(' ', '_') + ".txt";
			} else {
				newFilename = "filename.txt";
			}
		}
				
		editText.setText(newFilename);
		
		// highlight everything but the .txt at the end
		editText.setSelection(0, newFilename.length() - 4);
		
		return editText;
	}
		
	private void showFilenameSuggestingDialog(EditText editText, 
			DialogInterface.OnClickListener onClickListener, int titleResId) {
		
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		
		builder.setTitle(titleResId)
			.setCancelable(true)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, onClickListener)
			.setMessage(R.string.enter_filename)
			.setView(editText);
		
		builder.show();
		
	}

	private boolean checkSdCard() {
		
		boolean result = SaveFileHelper.checkIfSdCardExists();
		
		if (!result) {
			Toast.makeText(getApplicationContext(), R.string.sd_card_not_found, Toast.LENGTH_LONG).show();
		}
		return result;
	}

	@Override
	public void afterTextChanged(Editable s) {
		searchButton.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
		
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count,
			int after) {
		// do nothing
		
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before, int count) {
		// do nothing
		
	}	
	

	private void analyzeChordsEtcAndShowChordView() {

		// <-- find BPMs and AutoScrollSpeed
		int bpmTemp = (int) extractAutoScrollParam(chordText,"bpm");
		if (bpmTemp > 0)
			AutoScrollView.setBpm(bpmTemp);
		else {
			Toast.makeText(getApplicationContext(), R.string.No_BPM_found, Toast.LENGTH_LONG).show();
			AutoScrollView.setBpm(100);
		}

		float scrollVelocityCorrectionFactorTemp = extractAutoScrollParam(chordText,"scrollVelocityCorrectionFactor");
		if (scrollVelocityCorrectionFactorTemp > 0)
			viewingScrollView.setScrollVelocityCorrectionFactor(scrollVelocityCorrectionFactorTemp);
		else {
			viewingScrollView.setScrollVelocityCorrectionFactor(1);
		}

		String returnCheckAndAddAutoScrollParams = checkAndAddAutoScrollParams(chordText, viewingScrollView.getScrollVelocityCorrectionFactor());
		if (!returnCheckAndAddAutoScrollParams.isEmpty()) {
			chordText = returnCheckAndAddAutoScrollParams;
		}
		// -->

		chordsInText = ChordParser.findChordsInText(chordText, getNoteNaming());


		log.d("found %d chords", chordsInText.size());
		
		showChordView();
		
	}

	public static float extractAutoScrollParam(String text, String AutoScrollParam) {

		Matcher matcher;
		int matchGroup;

		if (AutoScrollParam.equals("bpm")) {
			Pattern bpmPattern = Pattern.compile("(\\d{2,3})(\\s*bpm)", Pattern.CASE_INSENSITIVE);
			matcher = bpmPattern.matcher(text);
			matchGroup = 1;
		} else if (AutoScrollParam.equals("scrollVelocityCorrectionFactor")) {
			Pattern scrollVelocityCorrectionFactorPattern = Pattern.compile("(autoscrollfactor|asf):?\\s*(\\d+[.|,]\\d+)", Pattern.CASE_INSENSITIVE);
			matcher = scrollVelocityCorrectionFactorPattern.matcher(text);
			matchGroup = 2;
		} else
			return -1;

		if (matcher.find()) {
			String match = matcher.group(matchGroup);
			if (match != null)
				match = match.replace(",",".");
			return Float.parseFloat(match);
		} else
			return -1;
	}

	public static String checkAndAddAutoScrollParams(String text, String scrollVelocityCorrectionFactor) {
		ArrayList<String> lines = new ArrayList<String>(Arrays.asList(StringUtil.split(text, "\n")));
		String firstLine = lines.get(0).toLowerCase();

		if (!(firstLine.contains("bpm") && firstLine.contains("autoscrollfactor"))) {
			lines.add(0, "*** " + AutoScrollView.getBpm() + " BPM - AutoScrollFactor: " + scrollVelocityCorrectionFactor + " ***");

			StringBuilder resultText = new StringBuilder();
			for (String line : lines) {
				resultText.append(line).append("\n");
			}
			return resultText.toString();
		}
		else
			return "";
	}

	private void changeAutoScrollFactor(boolean acceleration) {
		String toReplaceText = viewingTextView.getText().toString();
		String oldValue = viewingScrollView.getScrollVelocityCorrectionFactor();
		viewingScrollView.changeScrollVelocity(acceleration);
		String newValue = viewingScrollView.getScrollVelocityCorrectionFactor();
		chordText = toReplaceText.replace("AutoScrollFactor: " + oldValue + " ***", "AutoScrollFactor: " + newValue + " ***");
		chordsInText = ChordParser.findChordsInText(chordText, getNoteNaming());
		Spannable newText = buildUpChordTextToDisplay();
		applyLinkifiedChordsTextToTextView(newText);
		isEditedTextToSave = true;
	}

	private void showChordView() {
		
		// do in the background to avoid jankiness
		AsyncTask<Void,Void,Spannable> task = new AsyncTask<Void, Void, Spannable>(){

			@Override
			protected void onPreExecute() {
				super.onPreExecute();
			}

			@Override
			protected Spannable doInBackground(Void... params) {
				
				if (capoFret != 0 || transposeHalfSteps != 0) {
					updateChordsInTextForTransposition(-transposeHalfSteps, -capoFret);
				}
				
				Spannable newText = buildUpChordTextToDisplay();

				return newText;
			}

			@Override
			protected void onPostExecute(Spannable newText) {
				super.onPostExecute(newText);
				
				applyLinkifiedChordsTextToTextView(newText);
				viewingTextView.post(new Runnable() {
					@Override
					public void run() {
						viewingScrollView.calculateAutoScrollVelocity();
					}
				});
			}
			
		};
		
		task.execute((Void)null);
			
		
	}
	
	private void applyLinkifiedChordsTextToTextView(Spannable newText) {
		
		viewingTextView.setMovementMethod(MyLinkMovementMethod.getInstance());
		viewingTextView.setText(newText);

	}

	private Spannable buildUpChordTextToDisplay() {
		
		// have to build up a new string, because some of the chords may have different string lengths
		// than in the original text (e.g. if they are transposed)
		int lastEndIndex = 0;
		
		StringBuilder sb = new StringBuilder();
		
		List<Pair<Integer,Integer>> newStartAndEndPositions = 
			new ArrayList<Pair<Integer,Integer>>(chordsInText.size());
		
		for (ChordInText chordInText : chordsInText) {
			
			//log.d("chordInText is %s", chordInText);
			
			sb.append(chordText.substring(lastEndIndex, chordInText.getStartIndex()));
			
			String chordAsString = chordInText.getChord().toPrintableString(getNoteNaming());
			
			sb.append(chordAsString);
			
			newStartAndEndPositions.add(new Pair<Integer, Integer>(
					sb.length() - chordAsString.length(), sb.length()));
			
			lastEndIndex = chordInText.getEndIndex();
		}
		
		// append the last bit of text after the last chord
		sb.append(chordText.substring(lastEndIndex, chordText.length()));
		
		Spannable spannable = new Spannable.Factory().newSpannable(sb.toString());
		
		//log.d("new start and end positions are: %s", newStartAndEndPositions);
		
		// add a hyperlink to each chord
		for (int i = 0; i < newStartAndEndPositions.size(); i++) {
			
			Pair<Integer,Integer> newStartAndEndPosition = newStartAndEndPositions.get(i);
			
			//log.d("pair is %s", newStartAndEndPosition);
			//log.d("substr is '%s'", sb.substring(
			//		newStartAndEndPosition.getFirst(), newStartAndEndPosition.getSecond()));
			
			final Chord chord = chordsInText.get(i).getChord();
			
			InternalURLSpan urlSpan = new InternalURLSpan(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					showChordPopup(chord);
				}
			});
			
			spannable.setSpan(urlSpan, 
					newStartAndEndPosition.getFirst(), 
					newStartAndEndPosition.getSecond(), 
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}

		return spannable;
	}

	private void showChordPopup(final Chord chord) {
		
		if (!ChordDictionary.isInitialized()) {
			// it could take a second or two to initialize, so just wait until then...
			return;
		}
		
		final PopupWindow window = PopupHelper.newBasicPopupWindow(this);
		

		final LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		
		View view = inflater.inflate(R.layout.chord_popup, null);
		final TextView textView = (TextView) view.findViewById(android.R.id.text1);
		textView.setText(chord.toPrintableString(getNoteNaming()));
		
		final TextView textView2 = (TextView) view.findViewById(android.R.id.text2);
		textView2.setText(createGuitarChordText(chord));

		ImageButton chordVarEditButton = (ImageButton) view.findViewById(R.id.chord_var_edit_button);
		chordVarEditButton.setVisibility(View.VISIBLE);
		chordVarEditButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View view) {
				startChordEditActivity(chord);
				window.dismiss();
			}

		});
		
		window.setContentView(view);
		
		int[] textViewLocation = new int[2];
		viewingTextView.getLocationOnScreen(textViewLocation);
		
		int chordPopupOffset = Math.round(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_SP, CHORD_POPUP_Y_OFFSET_IN_SP, getResources().getDisplayMetrics()));
		
		int offsetX = Math.round(lastXCoordinate - textViewLocation[0]);
		int offsetY = Math.max(0, Math.round(lastYCoordinate - textViewLocation[1]) - chordPopupOffset);
		
		int heightOverride = getResources().getDimensionPixelSize(R.dimen.popup_height);
		
		PopupHelper.showLikeQuickAction(window, view, viewingTextView, getWindowManager(), offsetX, offsetY, heightOverride);
		
	}

	private CharSequence createGuitarChordText(Chord chord) {
		// TODO: have a better interface for switching between alternative ways of playing the same chord.
		// For now, just build up a list and show everything at once.
		
		List<String> guitarChords = ChordDictionary.getGuitarChordsForChord(chord);
		
		// Given how the dictionary is read in, these chords should have the simplest ones first
		// Just separate each with a number, if there is more than one
		
		switch (guitarChords.size()) {
			case 0:
				return getString(R.string.no_guitar_chord_available);
			case 1: 
				return guitarChords.get(0);
			default:
				// create a list
				StringBuilder stringBuilder = new StringBuilder();
				for (int i = 0; i < guitarChords.size(); i++) {
					stringBuilder
						.append(getString(R.string.variation))
						.append(' ')
						.append(i + 1)
						.append(": ")
						.append(guitarChords.get(i))
						.append('\n');
				}
				return stringBuilder.substring(0, stringBuilder.length() - 1); // cut off final newline
		}
	}

	private void startChordEditActivity(Chord chord) {
		Intent chordVarEditIntent = new Intent(getBaseContext(), ChordDictionaryEditActivity.class);
		//pass chord by intend
		chordVarEditIntent.putExtra("CHORD", chord);
		chordVarEditIntent.putExtra("NOTENAMING", getNoteNaming());
		startActivity(chordVarEditIntent);
	}

	private void switchToViewingMode() {

		wakeLock.acquire(30*60*1000L /*30 minutes*/);

		resetDataExceptChordTextAndFilename();
		
		searchingView.setVisibility(View.GONE);
		chordsViewingLayout.setVisibility(View.VISIBLE);

		autoscrollPauseButton.setVisibility(View.GONE);
		autoscrollPlayButton.setVisibility(View.VISIBLE);
		autoscrollSlowerButton.setVisibility(View.VISIBLE);
		autoscrollFasterButton.setVisibility(View.VISIBLE);
		stopAnimationMetronom();
		viewingScrollView.resetAutoScrollView();
		viewingScrollView.scrollTo(0,0);

		analyzeChordsEtcAndShowChordView();
	}
	
	private void switchToSearchingMode() {
		if (isEditedTextToSave) {
			showSavePromptDialog("switchToSearchingMode");
		}
		else {
			if (wakeLock.isHeld()) {
				wakeLock.release();
			}

			resetData();

			searchingView.setVisibility(View.VISIBLE);
			chordsViewingLayout.setVisibility(View.GONE);

			autoscrollPlayButton.setVisibility(View.GONE);
			autoscrollPauseButton.setVisibility(View.GONE);
			autoscrollSlowerButton.setVisibility(View.GONE);
			autoscrollFasterButton.setVisibility(View.GONE);
			viewingScrollView.resetAutoScrollView();
		}
	}

	private void resetData() {
		
		chordText = null;
		filename = null;
		viewingScrollView.resetAutoScrollView();

		resetDataExceptChordTextAndFilename();
		
	}
	
	private void resetDataExceptChordTextAndFilename() {
		

		chordsInText = null;
		if (filename != null) {
			ChordReaderDBHelper dbHelper = new ChordReaderDBHelper(this);
			Transposition transposition = dbHelper.findTranspositionByFilename(filename);
			dbHelper.close();
			if (transposition != null) {
				capoFret = transposition.getCapo();
				transposeHalfSteps = transposition.getTranspose();
			} else {
				capoFret = 0;
				transposeHalfSteps = 0;
			}
		} else {
			capoFret = 0;
			transposeHalfSteps = 0;
		}
		
	}

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		
		// record where the user touched so we know where to place the window, so it will be out of the way
		
		lastXCoordinate = event.getRawX();
		lastYCoordinate = event.getRawY();

		final int action = event.getAction();
		if (action == MotionEvent.ACTION_DOWN) { //press

			if (viewingScrollView.isAutoScrollOn()) {
				viewingScrollView.stopAutoScroll();

			}
			return super.onTouchEvent(event);
		}

		else if (action == MotionEvent.ACTION_UP) {
			if (viewingScrollView.isAutoScrollOn()) {
				viewingScrollView.postDelayed(new Runnable() { //wait a moment to check if a fling was caused
					@Override
					public void run() {
						if (!viewingScrollView.isFlingActive() && viewingScrollView.isAutoScrollOn()) {
							viewingScrollView.startAutoScroll();
						}
					}
				}, 100);
			}
			return super.onTouchEvent(event);
		}
		else
			return false;

	}
	
	private void applyColorScheme() {
		
		ColorScheme colorScheme = PreferenceHelper.getColorScheme(this);

		searchEditText.setTextColor(colorScheme.getForegroundColor(this));
		messageTextView.setTextColor(colorScheme.getForegroundColor(this));
		viewingTextView.setTextColor(colorScheme.getForegroundColor(this));
		mainView.setBackgroundColor(colorScheme.getBackgroundColor(this));
		viewingTextView.setLinkTextColor(ColorStateList.valueOf(colorScheme.getLinkColor(this)));
		
		messageSecondaryView.setBackgroundResource(colorScheme.getSelectorResource());
		
	}

	private void applySearchEngineURL() {
		searchEngineURL = PreferenceHelper.getSearchEngineURL(this);

	}

	// Animations
	protected void animationBlink(ImageButton imageButton) {
		Animation animButtonBlink = AnimationUtils.loadAnimation(this, R.anim.blink_anim);
		imageButton.startAnimation(animButtonBlink);
	}

	public void animationSwitch(final View view1, final View view2) {
		view1.setVisibility(View.GONE);
		view2.setVisibility(View.VISIBLE);
	}


	protected void startAnimationMetronome() {
		final long switchDuration = (long) (60d *1000) / AutoScrollView.getBpm();
		final LightingColorFilter lightningColorFilter = new LightingColorFilter(Color.rgb(50,160,186), Color.rgb(0,60,86));

		metronomTimer = new Timer();
		TimerTask metronomTimerTask = new TimerTask() {
			public void run() {
				metronomHandler.post(new Runnable() {
					public void run() {

						autoscrollPauseButton.setColorFilter(lightningColorFilter);
						autoscrollPauseButton.postDelayed(new Runnable() { //wait a moment to check if a fling was caused
							@Override
							public void run() {
								autoscrollPauseButton.clearColorFilter();
							}
						}, switchDuration/5);


					}
				});
			}
		};
		metronomTimer.schedule(metronomTimerTask, switchDuration, switchDuration);
	}

	protected void stopAnimationMetronom() {
		if(metronomTimer != null) {
			metronomTimer.cancel();
			metronomTimer.purge();
		}
	}

	// Autoscroll

	private void stopAutoscroll (){
		viewingScrollView.setAutoScrollOn(false);
		viewingScrollView.stopAutoScroll();
		stopAnimationMetronom();
		animationSwitch(autoscrollPauseButton, autoscrollPlayButton);
	}

	private void startAutoscroll(){
		animationSwitch(autoscrollPlayButton, autoscrollPauseButton);
		viewingScrollView.setAutoScrollOn(true);
		viewingScrollView.setAutoScrollActive(true);
		startAnimationMetronome();
		Handler handler = new Handler();
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (!viewingScrollView.isAutoScrollOn())
					return;
				viewingScrollView.startAutoScroll();
			}
		}, (long)(60d / AutoScrollView.getBpm() * 1000 * 4)); // delay of autoScroll start, to watch metronom firstly
	}


	private class CustomWebViewClient extends WebViewClient {
		
		private AtomicInteger taskCounter = new AtomicInteger(0);
		
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, final String url) {
			handler.post(new Runnable() {
				
				@Override
				public void run() {
					loadUrl(url);
					
				}
			});
			
			return true;
		}

		@Override
		public void onPageFinished(WebView view, final String url) {
			super.onPageFinished(view, url);
			log.d("onPageFinished()　" + url);
			
			if (url.contains(searchEngineURL)) {
				// trust google to only load once
				urlLoaded(url);
			} else { // don't trust other websites
				
				// have to do this song and dance because sometimes the pages
				// have a bazillion redirects, so I get multiple onPageFinished()
				// before the damn thing is really done, and then my users get confused
				// because the button says "page finished" before it really is
				// so we just wait a couple seconds for the dust to settle and make
				// sure that the web view is REALLY done loading
				// TODO: find a better way to do this
				
				AsyncTask<Void,Void,Void> task = new AsyncTask<Void, Void, Void>() {
	
					private int id;
					
					@Override
					protected void onPreExecute() {
						super.onPreExecute();
						id = taskCounter.incrementAndGet();
					}
	
					@Override
					protected Void doInBackground(Void... params) {
						try {
							Thread.sleep(PAGE_WAIT_TIME);
						} catch (InterruptedException e) {}
						return null;
					}
					
					@Override
					protected void onPostExecute(Void result) {
						super.onPostExecute(result);
						if (id == taskCounter.get()) {
							urlLoaded(url);
						}
					}
	
				};
				task.execute((Void)null);
			}
		}

		@Override
		public void onPageStarted(WebView view, String url, Bitmap favicon) {
			super.onPageStarted(view, url, favicon);
			log.d("onPageStarted()");
			taskCounter.incrementAndGet();
			urlLoading(url);
		}
	}

	private class MyGestureListener extends GestureDetector.SimpleOnGestureListener {

		@Override
		public boolean onDown(MotionEvent event) {
			// don't return false here or else none of the other
			// gestures will work
			if (viewingScrollView.isAutoScrollOn()) {
				viewingScrollView.stopAutoScroll();

			}

			return true;
		}

		@Override
		public boolean onSingleTapConfirmed(MotionEvent e) {

			return true;
		}

		@Override
		public void onLongPress(MotionEvent e) {

		}

		@Override
		public boolean onDoubleTap(MotionEvent e) {
			if (viewingScrollView.isAutoScrollOn())
				stopAutoscroll();
			else
				startAutoscroll();

			return true;
		}

		@Override
		public boolean onScroll(MotionEvent e1, MotionEvent e2,
								float distanceX, float distanceY) {

			return true;
		}

		@Override
		public boolean onFling(MotionEvent event1, MotionEvent event2,
							   float velocityX, float velocityY) {
			return true;
		}
	}

	// LinkMovementMethod/onTouchEvent needs to be adapted as otherwise linkified chord links will
	// be triggered till end of line/text view, when linkified chord is last on line
	public static class MyLinkMovementMethod extends LinkMovementMethod {
		private static MyLinkMovementMethod sInstance;

		public static MyLinkMovementMethod getInstance() {
			if (sInstance == null)
				sInstance = new MyLinkMovementMethod();
			return sInstance;
		}


		@Override
		public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
			int action = event.getAction();

			if (action == MotionEvent.ACTION_UP) {
				int x = (int) event.getX();
				int y = (int) event.getY();

				y -= widget.getTotalPaddingTop();
				y += widget.getScrollY();

				Layout layout = widget.getLayout();
				int line = layout.getLineForVertical(y);
				float lineLeft = layout.getLineLeft(line);
				float lineRight = layout.getLineRight(line);

				if (x > lineRight || (x >= 0 && x < lineLeft)) {
					return true;
				}
			}

			return super.onTouchEvent(widget, buffer, event);
		}
	}
}