package org.fossify.filemanager.adapters

import android.content.Intent
import android.media.RingtoneManager
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.TAB_FILES
import org.fossify.commons.helpers.TAB_STORAGE_ANALYSIS
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.SimpleActivity
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.fragments.MyViewPagerFragment
import org.fossify.filemanager.interfaces.FileManagerDependencies

class ViewPagerAdapter(
    val activity: SimpleActivity,
    val tabsToShow: ArrayList<Int>,
    private val intentProvider: () -> Intent,
    private val dependencies: FileManagerDependencies
) : PagerAdapter() {
    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = getFragment(position)
        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        (view as MyViewPagerFragment<*>).apply {
            val intent = intentProvider()
            val isPickRingtoneIntent = intent.action == RingtoneManager.ACTION_RINGTONE_PICKER
            val isGetContentIntent = intent.action == Intent.ACTION_GET_CONTENT
                    || intent.action == Intent.ACTION_PICK
            val isCreateDocumentIntent = intent.action == Intent.ACTION_CREATE_DOCUMENT
            val allowPickingMultipleIntent = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            val getContentMimeType = if (isGetContentIntent) {
                intent.type ?: ""
            } else {
                ""
            }

            val passedExtraMimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            val extraMimeTypes = if (isGetContentIntent && passedExtraMimeTypes != null) {
                passedExtraMimeTypes
            } else {
                null
            }

            this.isGetRingtonePicker = isPickRingtoneIntent
            this.isPickMultipleIntent = allowPickingMultipleIntent
            this.isGetContentIntent = isGetContentIntent
            wantedMimeTypes = extraMimeTypes?.toList() ?: listOf(getContentMimeType)
            updateIsCreateDocumentIntent(isCreateDocumentIntent)
            bindDependencies(dependencies)

            setupFragment(activity)
            onResume(activity.getProperTextColor())
        }

        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    override fun getCount(): Int {
        val showTabs = activity.config.showTabs
        var count = 0
        if (showTabs and TAB_FILES != 0) count++
        if (showTabs and TAB_STORAGE_ANALYSIS != 0) count++
        return count
    }

    override fun isViewFromObject(view: View, item: Any) = view == item

    private fun getFragment(position: Int): Int {
        val fragments = arrayListOf<Int>()
        val showTabs = activity.config.showTabs
        if (showTabs and TAB_FILES != 0) {
            fragments.add(R.layout.workspace_files_fragment)
        }
        if (showTabs and TAB_STORAGE_ANALYSIS != 0) {
            fragments.add(R.layout.storage_fragment)
        }
        return fragments[position]
    }
}
