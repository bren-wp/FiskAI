package hr.brendigo.fiskai

import org.json.JSONObject

internal object WorkspaceValidation {
    fun validate(records:JSONObject) {
        val drafts=DraftStore.decode(records.getJSONArray("fiskai_drafts/drafts"))
        val contacts=DirectoryStore.decodeContacts(records.getJSONArray("fiskai_directory/contacts"))
        val products=DirectoryStore.decodeProducts(records.getJSONArray("fiskai_directory/products"))
        require(drafts.map { it.id }.distinct().size==drafts.size)
        require(contacts.map { it.id }.distinct().size==contacts.size)
        require(products.map { it.id }.distinct().size==products.size)
        require(products.all{it.category.length<=80 && it.category.none{ch->ch.isISOControl()} && (it.unitCode.isEmpty()||it.unitCode in UblDraft.units)})
        for(draft in drafts) {
            require(draft.kind in setOf("Račun","Ponuda") && draft.customer.isNotBlank() && draft.lines.isNotEmpty())
            require(draft.oib.isBlank() || validOib(draft.oib))
            // Use existing arithmetic and reject invalid prices/quantities.
            for(line in draft.lines) require(line.quantity.signum()>0 && line.unitPrice.signum()>=0 && line.vatRate.signum()>=0)
            draft.total
        }
        FiscalSetupStore.decode(records.getJSONObject("fiskai_fiscal_setup/settings"))
        val profile=records.getJSONObject("fiskai_business/profile")
        for(key in listOf("name","oib","address","city","iban")) if(profile.has(key))require(profile.get(key) is String)
        if(profile.has("vatRegistered"))require(profile.get("vatRegistered") is Boolean)
    }
}
