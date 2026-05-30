package lin.weightHandler.warHandler

import lin.bean.ComboCard
import lin.bean.toDie
import lin.domain.MyWarManage
import lin.domain.war.SimpleCleanWar
import lin.utils.serviceLoader.ServiceLoaderUtils
import lin.warExt.my.attack.attackAfterLessBlood
import lin.warExt.my.attack.findMeAtc
import java.util.*

interface WarHandler {
    fun isToDie(comboCard: ComboCard, myWarManage: MyWarManage): Boolean
}

class ToDieHandler(val myWarManage: MyWarManage) {
    val warHandlers = ServiceLoaderUtils.loadServices(WarHandler::class.java)
    fun finToDie(canAttacks: List<ComboCard>, myWarManage: MyWarManage): MutableList<ComboCard> {
        val toDieList = LinkedList<ComboCard>()
        for (comboCard in canAttacks) {
            var toDie = comboCard.toDie()
            if (toDie) { //单一标识的处理
                toDieList.add(comboCard)
            } else {//通用的处理
                for (warHandler in warHandlers) {
                    toDie = warHandler.isToDie(comboCard, myWarManage)
                    if (toDie) {
                        toDieList.add(comboCard)
                        break
                    }
                }
            }

        }
        return toDieList
    }

    /**
     * 送亡语策略
     */
    fun processToDie() {
        val canAttacks = myWarManage.findMeAtc()
        if (canAttacks.isEmpty()) return

        val lessBlood = myWarManage.attackAfterLessBlood(canAttacks)
        //少于10血停止该操作
        if (lessBlood < 10) return

        val toDieList = finToDie(canAttacks, myWarManage)

        if (toDieList.isNotEmpty()) {
            val simpleCleanWar = SimpleCleanWar(toDieList, myWarManage.war.rival)
            simpleCleanWar.executeAttack()
            myWarManage.reloadPlayComboCards()
        }

    }
}
