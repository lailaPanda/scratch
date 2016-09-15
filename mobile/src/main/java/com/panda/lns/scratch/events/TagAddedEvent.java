package com.panda.lns.scratch.events;

import com.panda.lns.scratch.data.TagData;

public class TagAddedEvent {
    private TagData mTagData;

    public TagAddedEvent(TagData pTagData) {
        mTagData = pTagData;
    }

    public TagData getTag() {
        return mTagData;
    }
}
