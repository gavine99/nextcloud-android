/*
 * Nextcloud Android client application
 *
 * @author Alper Ozturk
 * Copyright (C) 2023 Alper Ozturk
 * Copyright (C) 2023 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.nextcloud.utils.extensions

import android.annotation.SuppressLint
import android.content.res.Resources

@SuppressLint("DiscouragedApi", "InternalInsetResource")
fun Resources.navBarHeight(): Int {
    val resourceId: Int = getIdentifier("navigation_bar_height", "dimen", "android")

    return if (resourceId > 0) {
        getDimensionPixelSize(resourceId)
    } else {
        0
    }
}

/**
 * This method only works in real device
 */
@SuppressLint("DiscouragedApi")
fun Resources.hasNavBar(): Boolean {
    val id = getIdentifier("config_showNavigationBar", "bool", "android")
    return id > 0 && getBoolean(id)
}
