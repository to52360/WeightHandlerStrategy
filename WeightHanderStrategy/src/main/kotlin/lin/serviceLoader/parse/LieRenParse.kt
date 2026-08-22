package lin.serviceLoader.parse

import lin.bean.CardWeightInfo


class LieRenParse : ParseCardWeightInfo {
    override fun parse(infoMap: Map<String, CardWeightInfo>) {
        infoMap["WW_807"]?.toDie = true


        /**
         *  临时 战士
         */
        infoMap["WW_367"]?.toDie = true
        //   infoMap["CORE_EX1_407"]?.addUseStrategy(AwaitAnimationStrategy)
        //  infoMap["DEEP_010"]?.addUseStrategy(AwaitAnimationStrategy)

    }
}