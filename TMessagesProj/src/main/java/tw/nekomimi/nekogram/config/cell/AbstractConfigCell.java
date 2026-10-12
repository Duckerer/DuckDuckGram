package tw.nekomimi.nekogram.config.cell;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.config.CellGroup;

public abstract class AbstractConfigCell {
    // can not be null!
    protected CellGroup cellGroup;

    // called by CellGroup.java
    public void bindCellGroup(CellGroup cellGroup) {
        this.cellGroup = cellGroup;
    }

    public List<CharSequence> getSearchTitles() {
        return Collections.emptyList();
    }

    public abstract int getType();

    public abstract boolean isEnabled();

    public abstract void onBindViewHolder(RecyclerView.ViewHolder holder);
}
