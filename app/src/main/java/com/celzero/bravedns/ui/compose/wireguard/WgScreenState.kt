/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import android.content.Context
import android.os.Bundle
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils

open class WgFormState {
    protected val editableFields = linkedMapOf<String, WgControlState>()
    fun saveForm(bundle: Bundle, prefix: String) { editableFields.forEach { (id, node) -> bundle.putString(prefix + id, node.text.toString()) } }
    fun restoreForm(bundle: Bundle?, prefix: String) { editableFields.forEach { (id, node) -> if (bundle?.containsKey(prefix + id) == true) node.setText(bundle.getString(prefix + id)) } }
}

class WgEditorState(context: Context) : WgFormState() {
    val wgConfigLayout = WgControlState(context).apply {
    }
    val interfaceTitle = WgControlState(context, textSource = "activity_wg_config_editor:interface_title").apply {
        applyTextAppearance(context, this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        text = context.getString(R.string.lbl_configure)
        textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary)
    }
    val interfaceNameLayout = WgControlState(context).apply {
        hint = context.getString(R.string.cd_dns_crypt_dialog_name)
    }
    val interfaceNameText = WgControlState(context).apply {
        noSuggestions = true
        imeNext = true
    }
    val privateKeyTextLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_private_key)
    }
    val privateKeyText = WgControlState(context).apply {
        isPassword = true
        noSuggestions = true
        imeNext = true
    }
    val publicKeyLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_public_key)
    }
    val publicKeyText = WgControlState(context).apply {
        maxLines = 1
        ellipsize = true
        hint = context.getString(R.string.lbl_generated)
        isFocusable = false
        imeNext = true
    }
    val addressesLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_addresses)
    }
    val addressesLabelText = WgControlState(context).apply {
        noSuggestions = true
        imeNext = true
    }
    val listenPortLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_listen_port)
    }
    val listenPortText = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_random)
        imeNext = true
    }
    val dnsServersLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_dns_servers)
    }
    val dnsServersText = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_auto)
        noSuggestions = true
        imeNext = true
    }
    val mtuLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_mtu)
    }
    val mtuText = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_auto)
    }
    val amzProps = WgControlState(context, textSource = "activity_wg_config_editor:amz_props").apply {
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
    }
    val lbActionContainer = WgControlState(context).apply {
    }
    val dismissBtn = WgControlState(context, textSource = "activity_wg_config_editor:dismiss_btn").apply {
        applyTextAppearance(context, this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        text = context.getString(R.string.lbl_dismiss)
        alpha = 0.7f
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
    }
    val saveTunnel = WgControlState(context, textSource = "activity_wg_config_editor:save_tunnel").apply {
        applyTextAppearance(context, this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        text = context.getString(R.string.lbl_save)
        textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary)
    }
    init {
        editableFields["interfaceNameText"] = interfaceNameText
        editableFields["privateKeyText"] = privateKeyText
        editableFields["publicKeyText"] = publicKeyText
        editableFields["addressesLabelText"] = addressesLabelText
        editableFields["listenPortText"] = listenPortText
        editableFields["dnsServersText"] = dnsServersText
        editableFields["mtuText"] = mtuText
    }
}

class WgPeerState(context: Context) : WgFormState() {
    val peerInputContainer = WgControlState(context).apply {
    }
    val peerTitle = WgControlState(context, textSource = "dialog_wg_add_peer:peer_title").apply {
        text = context.getString(R.string.add_peer)
        textSizeSp = wgTextDimensionSp(context, R.dimen.extra_large_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.secondaryTextColor)
    }
    val publicKeyLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_public_key)
    }
    val peerPublicKey = WgControlState(context).apply {
        noSuggestions = true
        imeNext = true
    }
    val preSharedKeyLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_preshared_key)
    }
    val peerPresharedKey = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_optional)
        isPassword = true
        noSuggestions = true
        imeNext = true
    }
    val persistentKeepaliveLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_persistent_keepalive)
    }
    val peerPersistentKeepAlive = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_optional)
        imeNext = true
    }
    val keepAliveHint = WgControlState(context, textSource = "dialog_wg_add_peer:keep_alive_hint").apply {
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val endpointLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.parse_error_inet_endpoint)
    }
    val peerEndpoint = WgControlState(context).apply {
        noSuggestions = true
        imeNext = true
    }
    val allowedIpsLabelLayout = WgControlState(context).apply {
        hint = context.getString(R.string.lbl_allowed_ips)
    }
    val peerAllowedIps = WgControlState(context).apply {
        maxLines = 4
        noSuggestions = true
    }
    val lbActionContainer = WgControlState(context).apply {
    }
    val customDialogDismissButton = WgControlState(context).apply {
        applyTextAppearance(context, this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        text = context.getString(R.string.lbl_dismiss)
        alpha = 0.7f
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
    }
    val customDialogOkButton = WgControlState(context).apply {
        applyTextAppearance(context, this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        text = context.getString(R.string.lbl_save)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
    }
    val customDialogOkProgress = WgControlState(context).apply {
        visibility = View.GONE
    }
    init {
        editableFields["peerPublicKey"] = peerPublicKey
        editableFields["peerPresharedKey"] = peerPresharedKey
        editableFields["peerPersistentKeepAlive"] = peerPersistentKeepAlive
        editableFields["peerEndpoint"] = peerEndpoint
        editableFields["peerAllowedIps"] = peerAllowedIps
    }
}

class WgSsidState(context: Context) : WgFormState() {
    val descriptionTextView = WgControlState(context, textSource = "dialog_wg_ssid:descriptionTextView").apply {
        textSizeSp = 14f
    }
    val ssidRecyclerView = WgControlState(context).apply {
        visibility = View.GONE
    }
    val emptyStateView = WgControlState(context, textSource = "dialog_wg_ssid:emptyStateView").apply {
        text = context.getString(R.string.country_ssid_empty_state)
        visibility = View.VISIBLE
        alpha = 0.6f
        textSizeSp = 14f
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val ssidConditionHeading = WgControlState(context, textSource = "dialog_wg_ssid:ssidConditionHeading").apply {
        text = context.getString(R.string.lbl_action)
        textSizeSp = 12f
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val ssidConditionRadioGroup = WgControlState(context).apply {
    }
    val radioEqual = WgControlState(context, checkable = true, textSource = "dialog_wg_ssid:radioEqual").apply {
        text = context.getString(R.string.lbl_connect)
        isChecked = true
    }
    val radioNotEqual = WgControlState(context, checkable = true, textSource = "dialog_wg_ssid:radioNotEqual").apply {
        text = context.getString(R.string.notification_action_pause_vpn)
    }
    val ssidMatchTypeRadioGroup = WgControlState(context).apply {
    }
    val radioExact = WgControlState(context, checkable = true, textSource = "dialog_wg_ssid:radioExact").apply {
        text = context.getString(R.string.wg_ssid_type_exact)
    }
    val radioWildcard = WgControlState(context, checkable = true, textSource = "dialog_wg_ssid:radioWildcard").apply {
        text = context.getString(R.string.wg_ssid_type_wildcard)
        isChecked = true
    }
    val ssidTextInputLayout = WgControlState(context).apply {
    }
    val ssidEditText = WgControlState(context).apply {
    }
    val addSsidBtn = WgControlState(context).apply {
        text = context.getString(R.string.lbl_add)
        textColor = UIUtils.fetchColor(context, R.attr.secondaryTextColor)
    }
    val cancelBtn = WgControlState(context).apply {
        text = context.getString(R.string.lbl_cancel)
        visibility = View.GONE
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val saveBtn = WgControlState(context).apply {
        text = context.getString(R.string.fapps_info_dialog_positive_btn)
        textColor = UIUtils.fetchColor(context, R.attr.secondaryTextColor)
    }
    init {
        editableFields["ssidEditText"] = ssidEditText
    }
    init {
        radioEqual.setOnCheckedChangeListener { _, checked -> if (checked) { radioNotEqual.isChecked = false; ssidConditionRadioGroup.callOnClick() } }
        radioNotEqual.setOnCheckedChangeListener { _, checked -> if (checked) { radioEqual.isChecked = false; ssidConditionRadioGroup.callOnClick() } }
        radioExact.setOnCheckedChangeListener { _, checked -> if (checked) { radioWildcard.isChecked = false; ssidMatchTypeRadioGroup.callOnClick() } }
        radioWildcard.setOnCheckedChangeListener { _, checked -> if (checked) { radioExact.isChecked = false; ssidMatchTypeRadioGroup.callOnClick() } }
    }
}

class WgHopState(context: Context) : WgFormState() {
    val wgHopDialogHeading = WgControlState(context, textSource = "dialog_wg_hop:wg_hop_dialog_heading").apply {
        applyTextAppearance(context, this, androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
        text = context.getString(R.string.hop_add_remove_title)
        textSizeSp = wgTextDimensionSp(context, R.dimen.extra_large_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val wgHopRecyclerView = WgControlState(context).apply {
        isClickable = true
        isFocusable = true
    }
    val wgHopDialogOkButton = WgControlState(context).apply {
        text = context.getString(R.string.ada_noapp_dialog_positive)
        textSizeSp = wgTextDimensionSp(context, R.dimen.large_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
}

class WgHopRowState(context: Context) : WgFormState() {
    val wgHopListCard = WgControlState(context).apply {
    }
    val wgHopListContainer = WgControlState(context).apply {
    }
    val wgHopListNameTv = WgControlState(context, textSource = "list_item_wg_hop:wg_hop_list_name_tv").apply {
        ellipsize = true
        textSizeSp = wgTextDimensionSp(context, R.dimen.extra_large_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
    }
    val wgHopListDescTv = WgControlState(context, textSource = "list_item_wg_hop:wg_hop_list_desc_tv").apply {
        ellipsize = true
        textSizeSp = wgTextDimensionSp(context, R.dimen.default_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val chipGroup = WgControlState(context, textSource = "list_item_wg_hop:chip_group").apply {
        visibility = View.VISIBLE
    }
    val chipIpv4 = WgControlState(context, textSource = "list_item_wg_hop:chip_ipv4").apply {
        text = context.getString(R.string.settings_ip_text_ipv4)
        visibility = View.GONE
        isChecked = true
        alpha = 0.8f
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        backgroundColor = UIUtils.fetchColor(context, R.attr.homeScreenBtnBackground)
        strokeColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val chipIpv6 = WgControlState(context, textSource = "list_item_wg_hop:chip_ipv6").apply {
        text = context.getString(R.string.settings_ip_text_ipv6)
        visibility = View.GONE
        isChecked = true
        alpha = 0.8f
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        backgroundColor = UIUtils.fetchColor(context, R.attr.homeScreenBtnBackground)
        strokeColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val chipSplitTunnel = WgControlState(context, textSource = "list_item_wg_hop:chip_split_tunnel").apply {
        text = context.getString(R.string.lbl_split)
        visibility = View.GONE
        isChecked = true
        alpha = 0.8f
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        backgroundColor = UIUtils.fetchColor(context, R.attr.homeScreenBtnBackground)
        strokeColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val chipAmnezia = WgControlState(context, textSource = "list_item_wg_hop:chip_amnezia").apply {
        text = context.getString(R.string.lbl_amnezia)
        visibility = View.GONE
        isChecked = true
        alpha = 0.8f
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        backgroundColor = UIUtils.fetchColor(context, R.attr.homeScreenBtnBackground)
        strokeColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val chipHopSrc = WgControlState(context, textSource = "list_item_wg_hop:chip_hop_src").apply {
        text = context.getString(R.string.lbl_hopping)
        visibility = View.GONE
        isChecked = true
        alpha = 0.8f
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        backgroundColor = UIUtils.fetchColor(context, R.attr.homeScreenBtnBackground)
        strokeColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val chipHopping = WgControlState(context, textSource = "list_item_wg_hop:chip_hopping").apply {
        text = context.getString(R.string.cd_dns_crypt_relay_heading)
        visibility = View.GONE
        isChecked = true
        alpha = 0.8f
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        backgroundColor = UIUtils.fetchColor(context, R.attr.homeScreenBtnBackground)
        strokeColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val chipProperties = WgControlState(context, textSource = "list_item_wg_hop:chip_properties").apply {
        text = context.getString(R.string.settings_ip_text_ipv4)
        visibility = View.GONE
        isChecked = true
        alpha = 0.8f
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        backgroundColor = UIUtils.fetchColor(context, R.attr.homeScreenBtnBackground)
        strokeColor = UIUtils.fetchColor(context, R.attr.accentGood)
    }
    val wgHopListCheckbox = WgControlState(context, checkable = true, textSource = "list_item_wg_hop:wg_hop_list_checkbox")
    val wgHopListProgress = WgControlState(context).apply {
        progressSource = "list_item_wg_hop:wg_hop_list_progress"
        isIndeterminate = true
        visibility = View.GONE
    }
}

class WgStatsState(context: Context) : WgFormState() {
    val toggleGroup = WgControlState(context).apply {
    }
    val tbRecentToggleBtn = WgControlState(context).apply {
        textSizeSp = wgTextDimensionSp(context, R.dimen.default_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.defaultToggleBtnTxt)
        isAllCaps = false
    }
    val tbDailyToggleBtn = WgControlState(context).apply {
        textSizeSp = wgTextDimensionSp(context, R.dimen.default_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.defaultToggleBtnTxt)
        isAllCaps = false
    }
    val tbWeeklyToggleBtn = WgControlState(context).apply {
        textSizeSp = wgTextDimensionSp(context, R.dimen.default_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.defaultToggleBtnTxt)
        isAllCaps = false
    }
    val tbLogsDisabledTv = WgControlState(context, textSource = "fragment_wg_nw_stats:tb_logs_disabled_tv").apply {
        text = context.getString(R.string.no_stats_available)
        visibility = View.VISIBLE
        textSizeSp = wgTextDimensionSp(context, R.dimen.large_font_text_view)
    }
    val tbStatsCard = WgControlState(context).apply {
        strokeWidth = (1f * context.resources.displayMetrics.density).toInt()
        strokeColor = UIUtils.fetchColor(context, R.attr.secondaryTextColor)
    }
    val fssProgressBar = WgControlState(context).apply {
        progressSource = "fragment_wg_nw_stats:fss_progress_bar"
        max = 100
        progress = 50
        secondaryProgress = 80
    }
    val fssUnmeteredDataUsage = WgControlState(context, textSource = "fragment_wg_nw_stats:fss_unmetered_data_usage").apply {
        applyTextAppearance(context, this, androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_subheading_text)
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val fssTotalDataUsage = WgControlState(context, textSource = "fragment_wg_nw_stats:fss_total_data_usage").apply {
        applyTextAppearance(context, this, androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_subheading_text)
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val fssMeteredDataUsage = WgControlState(context, textSource = "fragment_wg_nw_stats:fss_metered_data_usage").apply {
        applyTextAppearance(context, this, androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_subheading_text)
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val statsRecyclerView = WgControlState(context).apply {
    }
}

class WgStatsRowState(context: Context) : WgFormState() {
    val ssContainer = WgControlState(context).apply {
    }
    val ssIcon = WgControlState(context).apply {
        visibility = View.GONE
    }
    val ssFlag = WgControlState(context, textSource = "list_item_statistics_summary:ss_flag").apply {
        maxLines = 1
        textSizeSp = 18f
    }
    val ssDataUsage = WgControlState(context, textSource = "list_item_statistics_summary:ss_data_usage").apply {
        maxLines = 1
        ellipsize = true
        textSizeSp = wgTextDimensionSp(context, R.dimen.large_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
        textStyle = 1
    }
    val ssName = WgControlState(context, textSource = "list_item_statistics_summary:ss_name").apply {
        maxLines = 1
        ellipsize = true
        textSizeSp = wgTextDimensionSp(context, R.dimen.small_font_subheading_text)
        textColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)
    }
    val ssProgress = WgControlState(context).apply {
        progressSource = "list_item_statistics_summary:ss_progress"
        alpha = 0.9f
    }
    val ssCount = WgControlState(context, textSource = "list_item_statistics_summary:ss_count").apply {
        alpha = 0.85f
        textSizeSp = wgTextDimensionSp(context, R.dimen.default_font_text_view)
        textColor = UIUtils.fetchColor(context, R.attr.primaryTextColor)
    }
    val ssIndicator = WgControlState(context).apply {
        alpha = 0.35f
        imageResource = R.drawable.ic_right_arrow_white
    }
}

private fun applyTextAppearanceAttribute(context: Context, node: WgControlState, attribute: Int) {
    val value = android.util.TypedValue()
    if (context.theme.resolveAttribute(attribute, value, true) && value.resourceId != 0) applyTextAppearance(context, node, value.resourceId)
}
private fun applyTextAppearance(context: Context, node: WgControlState, style: Int) {
    // Keep the full appearance. The renderer resolves it before explicit presentation overrides.
    node.textAppearanceStyle = style
}

/** Preserve the source sp unit rather than divide already nonlinear-scaled pixels on Android 14+. */
internal fun wgTextDimensionSp(context: Context, resource: Int): Float {
    val value = android.util.TypedValue()
    context.resources.getValue(resource, value, true)
    return if (value.type == android.util.TypedValue.TYPE_DIMENSION &&
        (value.data and android.util.TypedValue.COMPLEX_UNIT_MASK) == android.util.TypedValue.COMPLEX_UNIT_SP) {
        android.util.TypedValue.complexToFloat(value.data)
    } else context.resources.getDimension(resource) / context.resources.displayMetrics.scaledDensity
}
