package com.duckduckgram.duckerer.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import com.duckduckgram.duckerer.updater.UpdaterSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

/**
 * Корневой экран «DuckDuckGram Preferences» — вход из главных настроек.
 *
 * Устроен как {@link app.exteraless.settings.OpenExteraSettingsActivity}: прозрачная
 * шапка, логотип, список категорий. Каждая категория — экран {@link DdgCategoryActivity}.
 */
public class DdgSettingsActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_ABOUT = 100;

    /** Одна строка списка категорий. */
    private static final class Category {
        final int titleRes;
        final int iconRes;
        /**
         * Создатель экрана категории. Явный, а не рефлексия: раньше здесь был
         * `cls.getDeclaredConstructor().newInstance()`, и в release-сборке R8 реагирует
         * на рефлексию непредсказуемо (может выкинуть конструктор класса) — папки
         * «Plugins» и «Local profile» при этом молча не открывались, а ошибка тонула
         * в catch. Явный вызов заодно проверяется компилятором.
         */
        final Supplier<org.telegram.ui.ActionBar.BaseFragment> factory;

        Category(int titleRes, int iconRes, Supplier<org.telegram.ui.ActionBar.BaseFragment> factory) {
            this.titleRes = titleRes;
            this.iconRes = iconRes;
            this.factory = factory;
        }
    }

    private static List<Category> categories() {
        List<Category> list = new ArrayList<>();
        list.add(new Category(R.string.DDGCategoryChats, R.drawable.msg_discussion, DdgChatsActivity::new));
        list.add(new Category(R.string.DDGCategoryInterface, R.drawable.msg_theme, DdgInterfaceActivity::new));
        list.add(new Category(R.string.DDGCategoryAppearance, R.drawable.msg_text_outlined, DdgAppearanceActivity::new));
        list.add(new Category(R.string.DDGCategoryPrivacy, R.drawable.msg_stealth_locked, DdgPrivacyActivity::new));
        // Java-плагины — отдельный движок и отдельный список; тот же экран, что
        // у Python, но с java-контроллером.
        list.add(new Category(R.string.DDGCategoryJavaPlugins, R.drawable.msg_plugins,
                app.exteraless.plugins.ui.PluginsActivity::javaInstance));
        list.add(new Category(R.string.DDGCategoryOther, R.drawable.msg_list, DdgOtherActivity::new));
        list.add(new Category(R.string.DDGCategoryAyuMoments, R.drawable.ayu_filter_all,
                tw.nekomimi.nekogram.settings.NekoAyuMomentsSettingsActivity::new));
        return list;
    }

    private final List<Category> categories = categories();
    /** Смещение строки категории в списке адаптера; -1, если это не категория. */
    private final List<Integer> categoryRowIndexes = new ArrayList<>();

    private int aboutRow;
    private int categoriesHeaderRow;
    private int categoriesDividerRow;
    private int linksHeaderRow;
    private int channelRow;
    private int sourceRow;

    private int linksDividerRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        aboutRow = addRow("about");

        categoriesHeaderRow = addRow("categoriesHeader");
        categoryRowIndexes.clear();
        for (int i = 0; i < categories.size(); i++) {
            // Ключ строки нужен только для уникальности в rowMap/ссылках настроек.
            categoryRowIndexes.add(addRow("ddg_category_" + i));
        }
        categoriesDividerRow = addRow();

        linksHeaderRow = addRow("linksHeader");
        channelRow = addRow("channel");
        sourceRow = addRow("source");
        linksDividerRow = addRow();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        if (actionBar != null && fragmentView instanceof FrameLayout) {
            actionBar.setBackground(null);
            actionBar.setCastShadows(false);
            actionBar.setAddToContainer(false);
            if (actionBar.getTitleTextView() != null) {
                actionBar.getTitleTextView().setAlpha(0f);
            }
            ((FrameLayout) fragmentView).addView(actionBar, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP));
        }
        return view;
    }

    @Override
    protected void setupAdaptiveBackground() {
    }

    @Override
    protected boolean needActionBarBlur() {
        return false;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, top, 0, bottom);
            listView.setClipToPadding(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.DuckDuckGramPreferences);
    }

    @Override
    public int getSearchGuid() {
        return 30000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.duckduckgram_icon_tile;
    }

    @Override
    public String getSearchPrefix() {
        return "DDG";
    }

    @Override
    protected String getKey() {
        return "duckduckgram";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        Category category = categoryAt(position);
        if (category != null) {
            try {
                presentFragment(category.factory.get());
            } catch (Exception e) {
                // Диагностика: если создание экрана падает, показываем текст ошибки.
                // Молчаливый catch оставлял бы папки «не открывающимися» без единой
                // улики, как было с Plugins/Local profile до перехода на явные Supplier.
                FileLog.e(e);
                if (getParentActivity() != null) {
                    AndroidUtilities.runOnUIThread(() -> new AlertDialog.Builder(getParentActivity())
                            .setTitle(org.telegram.messenger.LocaleController.getString(R.string.ErrorOccurred))
                            .setMessage(android.util.Log.getStackTraceString(e))
                            .setPositiveButton(org.telegram.messenger.LocaleController.getString(R.string.OK), null)
                            .show());
                }
            }
        } else if (position == channelRow) {
            getMessagesController().openByUserName("DuckDuckGram", this, 1);
        } else if (position == sourceRow) {
            org.telegram.messenger.browser.Browser.openUrl(getParentActivity(),
                    "https://github.com/Duckerer/DuckDuckGram");
        }
    }

    private Category categoryAt(int position) {
        int index = categoryRowIndexes.indexOf(position);
        return index < 0 ? null : categories.get(index);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_ABOUT) {
                DdgHeaderCell view = new DdgHeaderCell(mContext);
                view.setOnLogoClickListener(v -> showDialog(new UpdaterSheet(DdgSettingsActivity.this)));
                view.setTag(RecyclerListView.TAG_NOT_SECTION);
                view.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == categoriesHeaderRow) {
                        cell.setText(getString(R.string.DuckDuckGramPreferences));
                    } else if (position == linksHeaderRow) {
                        cell.setText(getString(R.string.DDGChannel));
                    }
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    Category category = categoryAt(position);
                    if (category != null) {
                        cell.setTextAndIcon(getString(category.titleRes), category.iconRes, true);
                    } else if (position == channelRow) {
                        cell.setTextAndValueAndIcon(getString(R.string.DDGChannel),
                                "@DuckDuckGram", R.drawable.msg_channel, true);
                    } else if (position == sourceRow) {
                        cell.setTextAndValueAndIcon(getString(R.string.DDGSourceCode),
                                "GitHub", R.drawable.msg_language, false);
                    }
                    cell.setImageLeft(21);
                    cell.setOffsetFromImage(71);
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(null);
                    cell.setBackground(Theme.getThemedDrawable(mContext,
                            R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_TEXT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == aboutRow) {
                return TYPE_ABOUT;
            } else if (position == categoriesHeaderRow || position == linksHeaderRow) {
                return TYPE_HEADER;
            } else if (position == categoriesDividerRow) {
                return TYPE_SHADOW;
            } else if (position == linksDividerRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_TEXT;
        }
    }
}
