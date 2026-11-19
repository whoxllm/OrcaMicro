package io.github.whoxllm.orcamicro.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.R

enum class OrcaAction(val resource: Int) {
    Statistics(R.drawable.print_statistics), Add(R.drawable.orca_add), Remove(R.drawable.orca_delete), Delete(R.drawable.orca_delete),
    Library(R.drawable.orca_menu_open), Save(R.drawable.orca_menu_save), Settings(R.drawable.orca_param_settings), Refresh(R.drawable.orca_refresh),
    PlateAdd(R.drawable.orca_toolbar_open), Move(R.drawable.orca_toolbar_move), Import(R.drawable.orca_menu_load), Export(R.drawable.orca_menu_save),
    Color(R.drawable.orca_objlist_color_painting), Menu(R.drawable.orca_canvas_menu), Edit(R.drawable.orca_menu_edit_preset), Center(R.drawable.orca_toolbar_arrange)
}
@Composable fun OrcaIcon(action: OrcaAction, description: String?, modifier: Modifier = Modifier.size(22.dp)) {
    Icon(painterResource(action.resource), description, modifier, tint = if(action == OrcaAction.Center || action == OrcaAction.Move || action == OrcaAction.PlateAdd || action == OrcaAction.Color || action == OrcaAction.Menu) Color.Unspecified else LocalContentColor.current)
}
