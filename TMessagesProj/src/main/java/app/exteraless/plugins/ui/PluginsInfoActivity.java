package app.exteraless.plugins.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

import app.exteraless.plugins.PluginsConstants;
import app.exteraless.plugins.PluginsController;

/**
 * Экран «Plugins Engine» — то, что у exteraGram открывается кнопкой (i) в шапке
 * списка плагинов. Порт
 * {@code com/exteragram/messenger/plugins/ui/PluginsInfoActivity.java}.
 *
 * Порядок секций и строк повторяет exteraGram: Settings (Developer Mode, Compact
 * View, Compatibility Mode, Safe Mode) → пояснение про safe mode → Python SDK
 * → Links → «Powered by Chaquopy».
 *
 * Расхождения, осознанные:
 *  - у exteraGram SDK докачивается с сервера (авто-обновление, бета-канал,
 *    «проверить обновления»); у нас SDK собран внутрь APK, поэтому вместо трёх
 *    неработающих переключателей — строка с его версией;
 *  - «Установить из файла» перенесено сюда с главного экрана: у exteraGram его нет
 *    вовсе (там ставят только тапом по .plugin), но убирать единственный
 *    надёжный способ установки было бы регрессом.
 */
public class PluginsInfoActivity extends BaseFragment {

    private static final int ID_DEVELOPER_MODE = 1;
    private static final int ID_COMPACT_VIEW = 2;
    private static final int ID_COMPATIBILITY = 3;
    private static final int ID_SAFE_MODE = 4;
    private static final int ID_SDK_VERSION = 5;
    private static final int ID_INSTALL_FROM_FILE = 6;
    private static final int ID_UNSAFE_MODE = 9;

    private final boolean javaHost;

    private PluginsController host() {
        return javaHost
                ? app.exteraless.plugins.JavaPluginsController.getInstance()
                : PluginsController.getInstance();
    }

    private UniversalRecyclerView listView;

    public PluginsInfoActivity() {
        this(false);
    }

    public PluginsInfoActivity(boolean javaHost) {
        this.javaHost = javaHost;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PluginsEngineTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        contentView.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        fragmentView = contentView;
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginsController controller = host();
        boolean engineOn = controller.isEngineEnabled();
        boolean safeMode = controller.isSafeMode();

        items.add(UItem.asHeader(getString(R.string.Settings)));
        items.add(PluginUiItem.check(ID_DEVELOPER_MODE,
                        getString(R.string.PluginsDeveloperMode),
                        R.drawable.msg_settings)
                .setChecked(controller.isDeveloperMode())
                .setEnabled(engineOn && !safeMode));
        items.add(PluginUiItem.check(ID_COMPACT_VIEW,
                        getString(R.string.PluginsCompactView),
                        R.drawable.msg_topics)
                .setChecked(controller.isCompactView())
                .setEnabled(engineOn));
        items.add(PluginUiItem.check(ID_COMPATIBILITY,
                        getString(R.string.PluginsCompatibilityMode),
                        R.drawable.msg_link2)
                .setChecked(controller.isCompatibilityMode())
                .setValue(getString(R.string.PluginsCompatibilityModeInfo))
                .setMultiline(true)
                .setEnabled(engineOn));
        items.add(PluginUiItem.check(ID_SAFE_MODE, getString(R.string.PluginsSafeMode),
                        R.drawable.msg_secret)
                .setChecked(safeMode));
        items.add(UItem.asShadow(getString(R.string.PluginsSafeModeSummary)));

        items.add(PluginUiItem.check(ID_UNSAFE_MODE, getString(R.string.PluginsUnsafeMode),
                        R.drawable.msg_warning)
                .setChecked(controller.isUnsafeMode())
                .setValue(getString(R.string.PluginsUnsafeModeInfo))
                .setMultiline(true)
                .setEnabled(engineOn && !safeMode));
        items.add(UItem.asShadow(getString(R.string.PluginsUnsafeModeSummary)));

        items.add(UItem.asHeader(getString(javaHost ? R.string.PluginsJavaSdk : R.string.PluginsPythonSdk)));
        items.add(UItem.asButton(ID_SDK_VERSION,
                getString(javaHost ? R.string.PluginsJavaSdk : R.string.PluginsPythonSdk))
                .setValue("v" + (javaHost
                        ? PluginsConstants.JAVA_SDK_VERSION : PluginsConstants.SDK_VERSION)));
        items.add(UItem.asButton(ID_INSTALL_FROM_FILE, getString(R.string.PluginsInstallFromFile))
                .accent()
                .setIcon(R.drawable.msg_add));
        items.add(UItem.asShadow(getString(R.string.PluginsPythonSdkInfo)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) {
            return;
        }
        PluginsController controller = host();
        if (item.id == ID_DEVELOPER_MODE) {
            controller.setDeveloperMode(!controller.isDeveloperMode());
        } else if (item.id == ID_COMPACT_VIEW) {
            controller.setCompactView(!controller.isCompactView());
        } else if (item.id == ID_COMPATIBILITY) {
            boolean enabled = !controller.isCompatibilityMode();
            controller.setCompatibilityMode(enabled);
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.contact_check, getString(R.string.RestartRequired))
                    .show();
        } else if (item.id == ID_SAFE_MODE) {
            controller.setSafeMode(!controller.isSafeMode());
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.contact_check, getString(R.string.RestartRequired))
                    .show();
        } else if (item.id == ID_UNSAFE_MODE) {
            if (controller.isUnsafeMode()) {
                controller.setUnsafeMode(false);
            } else {
                confirmUnsafeMode();
                return;
            }
        } else if (item.id == ID_INSTALL_FROM_FILE) {
            PluginsActivity.openPluginPicker(this);
            return;
        } else {
            return;
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void confirmUnsafeMode() {
        if (getParentActivity() == null) {
            return;
        }
        new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PluginsUnsafeMode))
                .setMessage(getString(R.string.PluginsUnsafeModeConfirm))
                .setPositiveButton(getString(R.string.PluginsUnsafeModeEnable), (dialog, which) -> {
                    host().setUnsafeMode(true);
                    if (listView != null) {
                        listView.adapter.update(true);
                    }
                    BulletinFactory.of(this)
                            .createSimpleBulletin(R.raw.chats_infotip,
                                    getString(R.string.PluginsUnsafeModeOn))
                            .show();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(org.telegram.ui.ActionBar.AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
            listView.setClipToPadding(false);
        }
    }
}
